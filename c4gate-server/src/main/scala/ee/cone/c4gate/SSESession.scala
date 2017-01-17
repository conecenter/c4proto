package ee.cone.c4gate

import java.util
import java.util.UUID

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4gate.InternetProtocol._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.concurrent.TrieMap


trait SSEServerApp extends ToStartApp with AssemblesApp with InitLocalsApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  lazy val pongHandler = new PongHandler(qMessages,worldProvider)
  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  private lazy val sseServer = new TcpServerImpl(ssePort, new SSEHandler(worldProvider))
  override def toStart: List[Executable] = sseServer :: super.toStart
  override def assembles: List[Assemble] = new TcpAssemble :: super.assembles
  override def initLocals: List[InitLocal] = sseServer :: pongHandler :: super.initLocals
}

case object LastPongKey extends WorldKey[String⇒Option[(String,Long)]](_⇒None)

class PongHandler(
    qMessages: QMessages, worldProvider: WorldProvider,
    pongs: TrieMap[String,(String,Long)] = TrieMap()
) extends RHttpHandler with InitLocal {
  def initLocal: (World) ⇒ World = LastPongKey.set(pongs.get)
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    if(httpExchange.getRequestURI.getPath != "/pong") return false
    val headers = httpExchange.getRequestHeaders.asScala.mapValues(v⇒Single(v.asScala.toList))
    val session = FromAlien(
      headers("X-r-session"),
      headers("X-r-location-search"),
      headers("X-r-location-hash")
    )
    pongs(session.sessionKey) = (headers("X-r-connection"), System.currentTimeMillis)
    Option(worldProvider.createTx()).filter{ local ⇒
      val world = TxKey.of(local).world
      val was = By.srcId(classOf[FromAlien]).of(world).getOrElse(session.sessionKey,Nil)
      was != List(session)
    }.map(LEvent.add(LEvent.update(session))).foreach(qMessages.send)
    httpExchange.sendResponseHeaders(200, 0)
    true
  }
}

case object AllowOriginKey extends WorldKey[Option[String]](None)
case object PostURLKey extends WorldKey[Option[String]](None)

object SSEMessageImpl {
  def connect(connectionKey: String): World ⇒ World = local ⇒ {
    val allowOrigin =
      AllowOriginKey.of(local).map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    val header = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    val data = s"$connectionKey ${PostURLKey.of(local).get}"
    send(connectionKey, "connect", data, header)(local)
  }
  def message(connectionKey: String, event: String, data: String): World ⇒ World =
    send(connectionKey, event, data, "")
  private def send(connectionKey: String, event: String, data: String, header: String): World ⇒ World = local => {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    for(s ← GetSenderKey.of(local)(connectionKey)) s.add(str.getBytes("UTF-8"))
    local
  }
}

class SSEHandler(worldProvider: WorldProvider) extends TcpHandler {
  override def beforeServerStart(): Unit = ()
  override def afterConnect(key: String): Unit = SSEMessageImpl.connect(key)(worldProvider.createTx())
  override def afterDisconnect(key: String): Unit = ()
}

case class SessionTxTransform(
    sessionKey: SrcId,
    fromAlien: FromAlien,
    writes: Values[ToAlienWrite]
) extends TxTransform {
  def transform(local: World): World = LastPongKey.of(local)(fromAlien.sessionKey) match {
    case None ⇒ ???
    case Some((connectionKey,lastPongTime)) ⇒
      def sender = GetSenderKey.of(local)(connectionKey)
      for(d ← tcpDisconnects; s ← sender) s.close()
      for(message ← writes; s ← sender) s.add(message.body.toByteArray)
      LEvent.add(writes.flatMap(LEvent.delete))(local)
  }
}

@assemble class TcpAssemble extends Assemble {
  type SessionKey = SrcId
  def joinToAlienWrite(
      key: SrcId,
      writes: Values[ToAlienWrite]
  ): Values[(SessionKey, ToAlienWrite)] =
    writes.map(write⇒write.sessionKey→write)
  def joinTxTransform(
      key: SrcId,
      fromAliens: Values[FromAlien],
      @by[SessionKey] writes: Values[ToAlienWrite]
  ): Values[(SrcId,TxTransform)] = List(key → (
    if(fromAliens.isEmpty) SimpleTxTransform(writes.flatMap(LEvent.delete))
    else SessionTxTransform(key, Single(fromAliens), writes.sortBy(_.priority))
  ))
}
