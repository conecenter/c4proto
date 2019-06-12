package ee.cone.c4gate

import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4gate.AlienProtocol.{FromAlienStatus, _}
import ee.cone.c4proto.Protocol

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.concurrent.TrieMap
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPost, S_HttpPublication}
import okio.ByteString

trait SSEServerApp
  extends ToStartApp
  with AssemblesApp
  with ToInjectApp
  with ProtocolsApp
{
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  def sseConfig: SSEConfig
  def mortal: MortalFactory
  lazy val pongHandler = new PongHandler(qMessages,worldProvider,sseConfig)
  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  private lazy val compressorFactory: StreamCompressorFactory = new GzipStreamCompressorFactory
  private lazy val sseServer =
    new TcpServerImpl(ssePort, new SSEHandler(worldProvider,sseConfig), 10, compressorFactory)
  override def toStart: List[Executable] = sseServer :: super.toStart
  override def assembles: List[Assemble] =
    SSEAssembles(mortal) ::: PostAssembles(mortal,sseConfig) :::
      super.assembles
  override def toInject: List[ToInject] =
    sseServer :: pongHandler :: super.toInject
  override def protocols: List[Protocol] = AlienProtocol :: super.protocols
}

case object LastPongKey extends SharedComponentKey[String⇒Option[Instant]]

class PongHandler(
    qMessages: QMessages, worldProvider: WorldProvider, sseConfig: SSEConfig,
    pongs: TrieMap[String,Instant] = TrieMap()
) extends RHttpHandler with ToInject with LazyLogging {
  def toInject: List[Injectable] = LastPongKey.set(pongs.get)
  def handle(httpExchange: HttpExchange, reqHeaders: List[N_Header]): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    if(httpExchange.getRequestURI.getPath != sseConfig.pongURL) return false
    val headers = reqHeaders.groupBy(_.key).map{ case(k,v) ⇒ k→Single(v).value }
    val now = Instant.now
    val local = worldProvider.createTx()
    val sessionKey = headers("X-r-session")
    val userName = ByPK(classOf[U_AuthenticatedSession]).of(local).get(sessionKey).map(_.userName)
    val session = FromAlienState(
      sessionKey,
      headers("X-r-location"),
      headers("X-r-connection"),
      userName
    )
    val refreshPeriodLong = sseConfig.stateRefreshPeriodSeconds*1L
    val status = FromAlienStatus(
      sessionKey,
      now.getEpochSecond /
        refreshPeriodLong *
        refreshPeriodLong +
        refreshPeriodLong +
        sseConfig.tolerateOfflineSeconds,
      isOnline = true
    )
    pongs(session.sessionKey) = now.plusSeconds(5)
    val wasSession = ByPK(classOf[FromAlienState]).of(local).get(session.sessionKey)
    val wasStatus = ByPK(classOf[FromAlienStatus]).of(local).get(status.sessionKey)
    TxAdd(
      (if(wasSession != Option(session)) LEvent.update(session) else Nil) ++
        (if(wasStatus != Option(status)) LEvent.update(status) else Nil)
    ).andThen(qMessages.send)(local)
    httpExchange.sendResponseHeaders(200, 0)
    //logger.debug(s"pong $headers")
    true
  }
}

object SSEMessage {
  def message(sender: SenderToAgent, event: String, data: String, header: String=""): Unit = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")    
    val str = s"${header}event: $event\ndata: $escapedData\n\n"     
    val unzippedStr = ByteString.encodeUtf8(s"event: $event\ndata: $escapedData\n\n")  
    val zippedStr =  sender.compressor.fold(unzippedStr)(compressor=>compressor.compress(unzippedStr))
    val toS = header.getBytes(UTF_8) ++ zippedStr.toByteArray
    //println(s"event: $event, unzipped: ${str.getBytes(UTF_8).length}, zipped: ${toS.length}")    
    sender.add(toS)
  }
}

class SSEHandler(worldProvider: WorldProvider, config: SSEConfig) extends TcpHandler with LazyLogging {
  override def beforeServerStart(): Unit = ()
  override def afterConnect(connectionKey: String, sender: SenderToAgent): Unit = {
    val allowOrigin =
      config.allowOrigin.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    val zipHeader = sender.compressor.fold("")(compressor =>
      s"Content-Encoding: ${compressor.name}\n"
    )
    val header = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$zipHeader$allowOrigin\n"
    val data = s"$connectionKey ${config.pongURL}"
    //logger.debug(s"connection $connectionKey")
    SSEMessage.message(sender, "connect", data, header)
  }
  override def afterDisconnect(key: String): Unit = ()
}

case object SSEPingTimeKey extends TransientLens[Instant](Instant.MIN)

case class SessionTxTransform( //?todo session/pongs purge
    sessionKey: SrcId,
    fromAlien: FromAlienState,
    status: FromAlienStatus,
    writes: Values[ToAlienWrite],
    availability: Option[Availability]
) extends TxTransform {
  def transform(local: Context): Context = {
    val now = Instant.now
    val connectionAliveUntil = LastPongKey.of(local)(sessionKey).getOrElse(Instant.MIN)
    val sender = GetSenderKey.of(local)(fromAlien.connectionKey)
    if(connectionAliveUntil.isBefore(now)) { //reconnect<precision<purge
      sender.foreach(_.close())
      val sessionAliveUntil = Instant.ofEpochSecond(status.expirationSecond)
      if(sessionAliveUntil.isBefore(now)) TxAdd(LEvent.delete(fromAlien))(local)
      else if(status.isOnline) TxAdd(LEvent.update(status.copy(isOnline = false)))(local)
      else local
    }
    else sender.map( sender ⇒
      ((local:Context) ⇒
        if(SECONDS.between(SSEPingTimeKey.of(local), now) < 1) local
        else {
          SSEMessage.message(sender, "ping", fromAlien.connectionKey)
          val availabilityAge = availability.map(a ⇒ a.until - now.toEpochMilli).mkString
          SSEMessage.message(sender, "availability", availabilityAge)
          SSEPingTimeKey.set(now)(local)
        }
      ).andThen{ local ⇒
        for(m ← writes) SSEMessage.message(sender, m.event, m.data)
        TxAdd(writes.flatMap(LEvent.delete))(local)
      }(local)
    ).getOrElse(local)
  }
}

object SSEAssembles {
  def apply(mortal: MortalFactory): List[Assemble] =
    new SSEAssemble ::
      mortal(classOf[FromAlienStatus]) ::
      mortal(classOf[ToAlienWrite]) :: Nil
}

@assemble class SSEAssembleBase   {
  type SessionKey = SrcId

  def joinToAlienWrite(
    key: SrcId,
    write: Each[ToAlienWrite]
  ): Values[(SessionKey, ToAlienWrite)] = List(write.sessionKey→write)

  def joinTxTransform(
    key: SrcId,
    session: Each[FromAlienState],
    status: Each[FromAlienStatus],
    @by[SessionKey] writes: Values[ToAlienWrite],
    @by[All] availabilities: Values[Availability]
  ): Values[(SrcId,TxTransform)] = List(WithPK(SessionTxTransform(
    session.sessionKey, session, status, writes.sortBy(_.priority), Single.option(availabilities)
  )))

  def lifeOfSessionToWrite(
    key: SrcId,
    fromAliens: Values[FromAlienState],
    @by[SessionKey] write: Each[ToAlienWrite]
  ): Values[(Alive,ToAlienWrite)] =
    if(fromAliens.nonEmpty) List(WithPK(write)) else Nil

  def lifeOfSessionPong(
    key: SrcId,
    fromAliens: Values[FromAlienState],
    status: Each[FromAlienStatus]
  ): Values[(Alive,FromAlienStatus)] =
    if(fromAliens.nonEmpty) List(WithPK(status)) else Nil

  def checkAuthenticatedSession(
    key: SrcId,
    fromAliens: Values[FromAlienState],
    authenticatedSession: Each[U_AuthenticatedSession]
  ): Values[(SrcId,TxTransform)] =
    if(fromAliens.isEmpty)
      List(WithPK(CheckAuthenticatedSessionTxTransform(authenticatedSession)))
    else Nil

  def allAvailability(
    key: SrcId,
    doc: Each[S_HttpPublication]
  ): Values[(All,Availability)] = for {
    until ← doc.until.toList if doc.path == "/availability"
  } yield All → Availability(doc.path,until)
}

case class Availability(path: String, until: Long)

case class CheckAuthenticatedSessionTxTransform(
  authenticatedSession: U_AuthenticatedSession
) extends TxTransform {
  def transform(local: Context) =
    if(Instant.ofEpochSecond(authenticatedSession.untilSecond).isBefore(Instant.now))
      TxAdd(LEvent.delete(authenticatedSession))(local)
    else local
}

case class NoProxySSEConfig(stateRefreshPeriodSeconds: Int) extends SSEConfig {
  def allowOrigin: Option[String] = Option("*")
  def pongURL: String = "/pong"
  def tolerateOfflineSeconds: Int = 60
  def sessionWaitingPosts: Int = 8
}
