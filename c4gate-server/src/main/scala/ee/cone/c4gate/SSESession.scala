package ee.cone.c4gate

import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4gate.AlienProtocol._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.concurrent.TrieMap
import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4gate.AuthProtocol.AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.{Header, HttpPost}

trait SSEServerApp
  extends `The PostLifeAssemble` with `The SSEAssemble`
  with `The ToInject`
  with `The TcpServerInject` with `The TcpServerExecutable`
  with `The TcpServerImpl` with `The SSETcpServerConfig` with `The SSEHandler`
  with `The AlienProtocol`
  with `The SSEAssembles` with `The PostAssembles`
  with `The PongImpl` with `The PongInject` with `The PongHandler`

case object LastPongKey extends SharedComponentKey[String⇒Option[Instant]]
@c4component @listed case class PongHandler(pong: Pong) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean = pong.handle(httpExchange)
}
@c4component @listed case class PongInject(pong: Pong) extends ToInject {
  def toInject: List[Injectable] = pong.toInject
}
trait Pong extends RHttpHandler {
  def toInject: List[Injectable]
}
@c4component case class PongImpl(
    qMessages: QMessages, worldProvider: WorldProvider, sseConfig: SSEConfig,
    pongs: TrieMap[String,Instant] = TrieMap()
) extends Pong {
  def toInject: List[Injectable] = LastPongKey.set(pongs.get)
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    if(httpExchange.getRequestURI.getPath != sseConfig.pongURL) return false
    val headers = httpExchange.getRequestHeaders.asScala.map{ case(k,v) ⇒ k→Single(v.asScala.toList) }
    val now = Instant.now
    val local = worldProvider.createTx()
    val sessionKey = headers("X-r-session")
    val userName = ByPK(classOf[AuthenticatedSession]).of(local).get(sessionKey).map(_.userName)
    val session = FromAlienState(
      sessionKey,
      headers("X-r-location"),
      headers("X-r-connection"),
      now.getEpochSecond / sseConfig.stateRefreshPeriodSeconds * sseConfig.stateRefreshPeriodSeconds,
      userName
    )
    pongs(session.sessionKey) = now
    val wasSession = ByPK(classOf[FromAlienState]).of(local).get(session.sessionKey)
    if(wasSession != Option(session))
      TxAdd(LEvent.update(session)).andThen(qMessages.send)(local)
    httpExchange.sendResponseHeaders(200, 0)
    true
  }
}

object SSEMessage {
  def message(sender: SenderToAgent, event: String, data: String, header: String=""): Unit = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    sender.add(str.getBytes(UTF_8))
  }
}

@c4component case class SSETcpServerConfig(config: Config) extends TcpServerConfig {
  def port: Int = config.get("C4SSE_PORT").toInt
  def timeout: Long = 10
}

@c4component case class SSEHandler(worldProvider: WorldProvider, config: SSEConfig) extends TcpHandler {
  override def beforeServerStart(): Unit = ()
  override def afterConnect(connectionKey: String, sender: SenderToAgent): Unit = {
    val allowOrigin =
      config.allowOrigin.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    val header = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    val data = s"$connectionKey ${config.pongURL}"
    SSEMessage.message(sender, "connect", data, header)
  }
  override def afterDisconnect(key: String): Unit = ()
}

case object SSEPingTimeKey extends TransientLens[Instant](Instant.MIN)

case class SessionTxTransform( //?todo session/pongs purge
    sessionKey: SrcId,
    fromAlien: FromAlienState,
    writes: Values[ToAlienWrite],
    purgePeriodSeconds: Int
) extends TxTransform {
  def transform(local: Context): Context = {
    val now = Instant.now
    val lastPongTime = LastPongKey.of(local)(sessionKey)
      .getOrElse(Instant.ofEpochSecond(fromAlien.lastPongSecond))
    val lastPongAge = SECONDS.between(lastPongTime,now)
    val sender = GetSenderKey.of(local)(fromAlien.connectionKey)

    if(lastPongAge>5) { //reconnect<precision<purge
      sender.foreach(_.close())
      if(lastPongAge>purgePeriodSeconds)
        TxAdd(LEvent.delete(fromAlien))(local) else local
    }
    else sender.map( sender ⇒
      ((local:Context) ⇒
        if(SECONDS.between(SSEPingTimeKey.of(local), now) < 1) local
        else {
          SSEMessage.message(sender, "ping", fromAlien.connectionKey)
          SSEPingTimeKey.set(now)(local)
        }
      ).andThen{ local ⇒
        for(m ← writes) SSEMessage.message(sender, m.event, m.data)
        TxAdd(writes.flatMap(LEvent.delete))(local)
      }(local)
    ).getOrElse(local)
  }
}

@c4component @listed case class SSEAssembles() extends Mortal(classOf[ToAlienWrite])

@assemble class SSEAssemble(sseConfig: SSEConfig) {
  type SessionKey = SrcId

  def joinToAlienWrite(
    key: SrcId,
    writes: Values[ToAlienWrite]
  ): Values[(SessionKey, ToAlienWrite)] =
    writes.map(write⇒write.sessionKey→write)

  def joinTxTransform(
    key: SrcId,
    fromAliens: Values[FromAlienState],
    @by[SessionKey] writes: Values[ToAlienWrite]
  ): Values[(SrcId,TxTransform)] =
    for(session ← fromAliens)
      yield WithPK(SessionTxTransform(
        session.sessionKey, session, writes.sortBy(_.priority),
        sseConfig.stateRefreshPeriodSeconds + sseConfig.tolerateOfflineSeconds
      ))

  def lifeOfSessionToWrite(
    key: SrcId,
    fromAliens: Values[FromAlienState],
    @by[SessionKey] writes: Values[ToAlienWrite]
  ): Values[(Alive,ToAlienWrite)] =
    for(write ← writes if fromAliens.nonEmpty) yield WithPK(write)
}

@c4component case class NoProxySSEConfig(config: Config)(val stateRefreshPeriodSeconds: Int = config.get("C4STATE_REFRESH_SECONDS").toInt) extends SSEConfig {
  def allowOrigin: Option[String] = Option("*")
  def pongURL: String = "/pong"
  def tolerateOfflineSeconds: Int = 60
  def sessionWaitingPosts: Int = 8
}
