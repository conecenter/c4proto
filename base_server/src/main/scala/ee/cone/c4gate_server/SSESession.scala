package ee.cone.c4gate_server

import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4gate.AlienProtocol.{U_FromAlienStatus, _}

import scala.collection.concurrent.TrieMap
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.S_HttpPublication
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest}
import ee.cone.c4di.{c4, provide}
import okio.ByteString

case object LastPongKey extends SharedComponentKey[String=>Option[Instant]]

trait PongRegistry {
  def pongs: TrieMap[String,Instant]
}

@c4("SSEServerApp") class PongRegistryImpl(val pongs: TrieMap[String,Instant] = TrieMap()) extends PongRegistry with ToInject {
  def toInject: List[Injectable] = LastPongKey.set(pongs.get)
}

class PongHandler(
  sseConfig: SSEConfig, pongRegistry: PongRegistry,
  httpResponseFactory: RHttpResponseFactory, next: RHttpHandler
) extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse =
    if(request.method == "POST" && request.path == sseConfig.pongURL) {
      logger.debug(s"pong-enter")
      val headers = request.headers.groupMap(_.key)(_.value).transform((k,v) => Single(v))
      headers.get("x-r-session").filter(_.nonEmpty).fold{ //start
        logger.debug(s"pong-y-start")
        httpResponseFactory.setSession(request,Option(""),None)
      }{ sessionKey =>
        logger.debug(s"pong-n-start")
        ByPK(classOf[U_AuthenticatedSession]).of(local).get(sessionKey).fold{
          logger.debug(s"pong-reset")
          httpResponseFactory.setSession(request,None,None)
        }{ aSession =>
          logger.debug(s"pong-normal")
          val session = U_FromAlienState(
            sessionKey,
            headers("x-r-location"),
            headers("x-r-connection"),
            Option(aSession).map(_.userName).filter(_.nonEmpty)
          )
          val now = Instant.now
          val refreshPeriodLong = sseConfig.stateRefreshPeriodSeconds*1L
          val status = U_FromAlienStatus(
            session.sessionKey,
            now.getEpochSecond /
              refreshPeriodLong *
              refreshPeriodLong +
              refreshPeriodLong +
              sseConfig.tolerateOfflineSeconds,
            isOnline = true
          )
          pongRegistry.pongs(session.sessionKey) = now.plusSeconds(5)
          val wasSession = ByPK(classOf[U_FromAlienState]).of(local).get(session.sessionKey)
          val wasStatus = ByPK(classOf[U_FromAlienStatus]).of(local).get(status.sessionKey)
          val events: Seq[LEvent[Product]] =
            (if(wasSession != Option(session)) LEvent.update(session) else Nil) ++
              (if(wasStatus != Option(status)) LEvent.update(status) else Nil)
          logger.debug(s"pong-events ${events.size}")
          httpResponseFactory.directResponse(request,r=>r).copy(events=events.toList)
        }
      }
    } else next.handle(request,local)
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

class SSEHandler(config: SSEConfig) extends TcpHandler with LazyLogging {
  override def beforeServerStart(): Unit = ()
  override def afterConnect(connectionKey: String, sender: SenderToAgent): Unit = {
    val allowOrigin =
      config.allowOrigin.map(v=>s"access-control-allow-origin: $v\n").getOrElse("")
    val zipHeader = sender.compressor.fold("")(compressor =>
      s"content-encoding: ${compressor.name}\n"
    )
    val header = s"HTTP/1.1 200 OK\ncontent-type: text/event-stream\n$zipHeader$allowOrigin\n"
    val data = s"$connectionKey ${config.pongURL}"
    //logger.debug(s"connection $connectionKey")
    SSEMessage.message(sender, "connect", data, header)
  }
  override def afterDisconnect(key: String): Unit = ()
}

case object SSEPingTimeKey extends TransientLens[Instant](Instant.MIN)

case class SessionTxTransform( //?todo session/pongs purge
    session: U_AuthenticatedSession,
    fromAlien: Option[U_FromAlienState],
    status: Option[U_FromAlienStatus],
    writes: Values[U_ToAlienWrite],
    availability: Option[Availability]
) extends TxTransform {
  def transform(local: Context): Context = {
    val now = Instant.now
    val connectionAliveUntil = LastPongKey.of(local)(session.sessionKey).getOrElse(Instant.MIN)
    val connectionKey = fromAlien.map(_.connectionKey)
    val sender = connectionKey.flatMap(GetSenderKey.of(local))
    if(connectionAliveUntil.isBefore(now)) { //reconnect<precision<purge
      sender.foreach(_.close())
      val sessionAliveUntil = Instant.ofEpochSecond(status.fold(session.untilSecond)(_.expirationSecond))
      if(sessionAliveUntil.isBefore(now)) TxAdd(LEvent.delete(session))(local)
      else TxAdd(status.filter(_.isOnline).map(_.copy(isOnline = false)).toList.flatMap(LEvent.update(_)))(local)
    }
    else sender.map( sender =>
      ((local:Context) =>
        if(SECONDS.between(SSEPingTimeKey.of(local), now) < 1) local
        else {
          SSEMessage.message(sender, "ping", connectionKey.get)
          val availabilityAge = availability.map(a => a.until - now.toEpochMilli).mkString
          SSEMessage.message(sender, "availability", availabilityAge)
          SSEPingTimeKey.set(now)(local)
        }
      ).andThen{ local =>
        for(m <- writes) SSEMessage.message(sender, m.event, m.data)
        TxAdd(writes.flatMap(LEvent.delete))(local)
      }(local)
    ).getOrElse(local)
  }
}

@c4("SSEServerApp") class SSEAssembles(mortal: MortalFactory) {
  @provide def subAssembles: Seq[Assemble] =
    mortal(classOf[U_FromAlienStatus]) :: mortal(classOf[U_ToAlienWrite]) :: Nil
}

@c4assemble("SSEServerApp") class SSEAssembleBase   {
  type SessionKey = SrcId

  def joinToAlienWrite(
    key: SrcId,
    write: Each[U_ToAlienWrite]
  ): Values[(SessionKey, U_ToAlienWrite)] = List(write.sessionKey->write)

  def joinTxTransform(
    key: SrcId,
    session: Each[U_AuthenticatedSession],
    sessionStates: Values[U_FromAlienState],
    statuses: Values[U_FromAlienStatus],
    @by[SessionKey] writes: Values[U_ToAlienWrite],
    @byEq[AbstractAll](All) availabilities: Values[Availability]
  ): Values[(SrcId,TxTransform)] = List(WithPK(SessionTxTransform(
    session, Single.option(sessionStates), Single.option(statuses),
    writes.sortBy(_.priority), Single.option(availabilities)
  )))

  def lifeOfSessionToWrite(
    key: SrcId,
    fromAliens: Values[U_FromAlienState],
    @by[SessionKey] write: Each[U_ToAlienWrite]
  ): Values[(Alive,U_ToAlienWrite)] =
    if(fromAliens.nonEmpty) List(WithPK(write)) else Nil

  def lifeOfSessionPong(
    key: SrcId,
    fromAliens: Values[U_FromAlienState],
    status: Each[U_FromAlienStatus]
  ): Values[(Alive,U_FromAlienStatus)] =
    if(fromAliens.nonEmpty) List(WithPK(status)) else Nil

  def lifeOfSessionState(
    key: SrcId,
    sessions: Values[U_AuthenticatedSession],
    state: Each[U_FromAlienState]
  ): Values[(Alive,U_FromAlienState)] =
    if(sessions.nonEmpty) List(WithPK(state)) else Nil

  def allAvailability(
    key: SrcId,
    doc: Each[S_HttpPublication]
  ): Values[(AbstractAll,Availability)] = for {
    until <- doc.until.toList if doc.path == "/availability"
  } yield All -> Availability(doc.path,until)
}

case class Availability(path: String, until: Long)

@c4("NoProxySSEConfigApp") case class NoProxySSEConfig()(config: Config) extends SSEConfig {
  def stateRefreshPeriodSeconds: Int = config.get("C4STATE_REFRESH_SECONDS").toInt
  def allowOrigin: Option[String] = Option("*")
  def pongURL: String = "/pong"
  def tolerateOfflineSeconds: Int = 60
  def sessionWaitingRequests: Int = 8
}
