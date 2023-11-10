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
import ee.cone.c4gate.{ByPathHttpPublication, ByPathHttpPublicationUntil}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest}
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4gate_server.RHttpTypes.RHttpHandlerCreate
import okio.ByteString

trait PongRegistry {
  def pongs: TrieMap[String,Instant]
}
trait RoPongRegistry {
  def get: String=>Option[Instant]
}

@c4("SSEServerApp") final class PongRegistryImpl(
  val pongs: TrieMap[String,Instant] = TrieMap()
) extends PongRegistry

@c4("SSEServerApp") final class RoPongRegistryImpl(
  rw: PongRegistry
) extends RoPongRegistry {
  def get: String=>Option[Instant] = rw.pongs.get
}

@c4("SSEServerApp") final class PongHandler(
  sseConfig: SSEConfig, pongRegistry: PongRegistry,
  httpResponseFactory: RHttpResponseFactory,
  updateIfChanged: UpdateIfChanged,
  getU_AuthenticatedSession: GetByPK[U_AuthenticatedSession],
  getU_FromAlienState: GetByPK[U_FromAlienState],
  getU_FromAlienStatus: GetByPK[U_FromAlienStatus],
  getU_FromAlienConnected: GetByPK[U_FromAlienConnected],
) extends LazyLogging {
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if(request.method == "POST" && request.path == sseConfig.pongURL) {
      logger.debug(s"pong-enter")
      val headers = request.headers.filter(_.key.startsWith("x-r-")).groupMap(_.key)(_.value).transform((k,v) => Single(v))
      headers.get("x-r-session").filter(_.nonEmpty).fold{ //start
        logger.debug(s"pong-y-start")
        httpResponseFactory.setSession(request,Option(""),None)
      }{ sessionKey =>
        logger.debug(s"pong-n-start")
        getU_AuthenticatedSession.ofA(local).get(sessionKey).fold{
          logger.debug(s"pong-reset")
          httpResponseFactory.setSession(request,None,None)
        }{ aSession =>
          logger.debug(s"pong-normal")
          val session = U_FromAlienState(
            sessionKey,
            headers("x-r-location"),
            headers("x-r-reload"),
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
          val connected = U_FromAlienConnected(sessionKey,headers("x-r-connection"))
          pongRegistry.pongs(session.sessionKey) = now.plusSeconds(5)
          val events: Seq[LEvent[Product]] =
            updateIfChanged.updateSimple(getU_FromAlienState)(local)(Seq(session)) ++
            updateIfChanged.updateSimple(getU_FromAlienStatus)(local)(Seq(status)) ++
            updateIfChanged.updateSimple(getU_FromAlienConnected)(local)(Seq(connected))
          logger.debug(s"pong-events ${events.size}")
          httpResponseFactory.directResponse(request,r=>r).copy(events=events.toList)
        }
      }
    } else next(request,local)
}

object SSEMessage {
  def message(sender: SenderToAgent, event: String, data: String, header: String=""): Unit = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val unzippedArr: Array[Byte] = s"event: $event\ndata: $escapedData\n\n".getBytes(UTF_8)
    val zippedArr: Array[Byte] =  sender.compressor.fold(unzippedArr)(compressor=>compressor.compress(unzippedArr))
    val toS = header.getBytes(UTF_8) ++ zippedArr
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

@c4multi("SSEServerApp") final case class SessionTxTransform( //?todo session/pongs purge
    sessionKey: SrcId,
    session: Option[U_AuthenticatedSession],
    connected: Option[U_FromAlienConnected],
    status: Option[U_FromAlienStatus],
    writes: Values[U_ToAlienWrite],
    availability: Option[Availability]
)(
  txAdd: LTxAdd,
  lastPongs: RoPongRegistry,
  server: TcpServer,
) extends TxTransform {
  def transform(local: Context): Context = {
    val now = Instant.now
    val connectionAliveUntil = lastPongs.get(sessionKey).getOrElse(Instant.MIN)
    val connectionKey = connected.map(_.connectionKey)
    val sender = connectionKey.flatMap(server.getSender)
    if(connectionAliveUntil.isBefore(now)) { //reconnect<precision<purge
      sender.foreach(_.close())
      val sessionAliveUntil =
          status.map(_.expirationSecond).orElse(session.map(_.untilSecond)).fold(Instant.MIN)(Instant.ofEpochSecond)
      if(sessionAliveUntil.isBefore(now))
        txAdd.add(session.toList.flatMap(LEvent.delete) ++ connected.toList.flatMap(LEvent.delete))(local)
      else txAdd.add(status.filter(_.isOnline).map(_.copy(isOnline = false)).toList.flatMap(LEvent.update(_)))(local)
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
        txAdd.add(writes.flatMap(LEvent.delete))(local)
      }(local)
    ).getOrElse(local)
  }
}

@c4("SSEServerApp") final class SSEAssembles(mortal: MortalFactory) {
  @provide def subAssembles: Seq[Assemble] =
    mortal(classOf[U_FromAlienState]) :: mortal(classOf[U_FromAlienStatus]) :: mortal(classOf[U_ToAlienWrite]) :: Nil
}

@c4assemble("SSEServerApp") class SSEAssembleBase(
  sessionTxTransformFactory: SessionTxTransformFactory
){
  type SessionKey = SrcId

  def joinToAlienWrite(
    key: SrcId,
    write: Each[U_ToAlienWrite]
  ): Values[(SessionKey, U_ToAlienWrite)] = List(write.sessionKey->write)

  def joinTxTransform(
    key: SrcId,
    sessions: Values[U_AuthenticatedSession],
    connected: Values[U_FromAlienConnected],
    statuses: Values[U_FromAlienStatus],
    @by[SessionKey] writes: Values[U_ToAlienWrite],
    @byEq[AbstractAll](All) availabilities: Values[Availability]
  ): Values[(SrcId,TxTransform)] = List(WithPK(sessionTxTransformFactory.create(key,
    Single.option(sessions), Single.option(connected), Single.option(statuses),
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
    doc: Each[ByPathHttpPublicationUntil]
  ): Values[(AbstractAll,Availability)] =
    if(doc.path == "/availability")
      List(All -> Availability(doc.path,doc.until)) else Nil
}

case class Availability(path: String, until: Long)

@c4("NoProxySSEConfigApp") final case class NoProxySSEConfig()(config: Config) extends SSEConfig {
  def stateRefreshPeriodSeconds: Int = config.get("C4STATE_REFRESH_SECONDS").toInt
  def allowOrigin: Option[String] = Option("*")
  def pongURL: String = "/pong"
  def tolerateOfflineSeconds: Int = 60
  def sessionWaitingRequests: Int = 8
}
