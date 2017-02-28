package ee.cone.c4gate

import java.time.Instant

import java.time.temporal.ChronoUnit.SECONDS

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4gate.AlienProtocol._
import ee.cone.c4proto.Protocol

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.concurrent.TrieMap

trait SSEServerApp extends ToStartApp with AssemblesApp with InitLocalsApp with ProtocolsApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  def sseConfig: SSEConfig
  lazy val pongHandler = new PongHandler(qMessages,worldProvider,sseConfig)
  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  private lazy val sseServer = new TcpServerImpl(ssePort, new SSEHandler(worldProvider,sseConfig))
  override def toStart: List[Executable] = sseServer :: super.toStart
  override def assembles: List[Assemble] = new SSEAssemble :: super.assembles
  override def initLocals: List[InitLocal] =
    sseServer :: pongHandler :: super.initLocals
  override def protocols: List[Protocol] = AlienProtocol :: super.protocols
}

case object LastPongKey extends WorldKey[String⇒Option[Instant]](_⇒None)

class PongHandler(
    qMessages: QMessages, worldProvider: WorldProvider, sseConfig: SSEConfig,
    pongs: TrieMap[String,Instant] = TrieMap()
) extends RHttpHandler with InitLocal {
  def initLocal: (World) ⇒ World = LastPongKey.set(pongs.get)
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    if(httpExchange.getRequestURI.getPath != sseConfig.pongURL) return false
    val headers = httpExchange.getRequestHeaders.asScala.map{ case(k,v) ⇒ k→Single(v.asScala.toList) }
    val now = Instant.now
    val session = FromAlienState(
      headers("X-r-session"),
      headers("X-r-location"),
      headers("X-r-connection"),
      now.getEpochSecond / 100 * 100
    )
    pongs(session.sessionKey) = now
    Option(worldProvider.createTx()).filter{ local ⇒
      val world = TxKey.of(local).world
      val was = By.srcId(classOf[FromAlienState]).of(world).getOrElse(session.sessionKey,Nil)
      was != List(session)
    }.map(LEvent.add(LEvent.update(session))).foreach(qMessages.send)
    httpExchange.sendResponseHeaders(200, 0)
    true
  }
}

object SSEMessage {
  def message(sender: SenderToAgent, event: String, data: String, header: String=""): Unit = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    sender.add(str.getBytes("UTF-8"))
  }
}

class SSEHandler(worldProvider: WorldProvider, config: SSEConfig) extends TcpHandler {
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

case object SSEPingTimeKey extends WorldKey[Instant](Instant.MIN)

case class SessionTxTransform( //?todo session/pongs purge
    sessionKey: SrcId,
    fromAlien: FromAlienState,
    writes: Values[ToAlienWrite]
) extends TxTransform {
  def transform(local: World): World = {
    val now = Instant.now
    val lastPongTime = LastPongKey.of(local)(sessionKey)
      .getOrElse(Instant.ofEpochSecond(fromAlien.lastPongSecond))
    val lastPongAge = SECONDS.between(lastPongTime,now)
    val sender = GetSenderKey.of(local)(fromAlien.connectionKey)

    if(lastPongAge>2) {
      sender.foreach(_.close())
      if(lastPongAge>120) LEvent.add(LEvent.delete(fromAlien))(local) else local
    }
    else sender.map( sender ⇒
      ((local:World) ⇒
        if(SECONDS.between(SSEPingTimeKey.of(local), now) < 1) local
        else {
          SSEMessage.message(sender, "ping", fromAlien.connectionKey)
          SSEPingTimeKey.set(now)(local)
        }
      ).andThen{ local ⇒
        for(m ← writes) SSEMessage.message(sender, m.event, m.data)
        LEvent.add(writes.flatMap(LEvent.delete))(local)
      }(local)
    ).getOrElse(local)
  }
}

@assemble class SSEAssemble extends Assemble {
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
  ): Values[(SrcId,TxTransform)] = List(key → (
    if(fromAliens.isEmpty) SimpleTxTransform(key, writes.flatMap(LEvent.delete))
    else SessionTxTransform(key, Single(fromAliens), writes.sortBy(_.priority))
  ))
}

object NoProxySSEConfig extends SSEConfig {
  def allowOrigin: Option[String] = Option("*")
  def pongURL: String = "/pong"
}
