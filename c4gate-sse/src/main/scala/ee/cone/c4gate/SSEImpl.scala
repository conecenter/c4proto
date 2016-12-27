package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4gate.SSEProtocol.{ConnectionPingState, ConnectionPongState}

class SSEMessagesImpl(allowOriginOption: Option[String]) extends SSEMessages {
  def message(connectionKey: String, event: String, data: String, priority: Long): TcpWrite = {
    val header = if(priority > 0) "" else {
      val allowOrigin =
        allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
      s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    }
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    val bytes = okio.ByteString.encodeUtf8(str)
    val key = UUID.randomUUID.toString
    TcpWrite(key,connectionKey,bytes,priority)
  }
}

class SSETxTransform(sseMessages: SSEMessages) extends TxTransform {
  def transform(tx: WorldTx): WorldTx = {
    val time = System.currentTimeMillis
    val connections = By.srcId(classOf[SSEConnection]).of(tx.world)
    val events = connections.values.flatten.toSeq.flatMap(conn⇒conn.wishes(time))
    tx.add(events:_*)
  }
}

case class WorkingSSEConnection(
  connectionKey: String,
  pingState: ConnectionPingState,
  pongState: Option[ConnectionPongState],
  posts: List[HttpPostByConnection]
)(sseMessages: SSEMessages) extends SSEConnection {
  def wishes(time: Long): Seq[LEvent[_]] = {
    val pongs =
      posts.filter(post ⇒ post.headers.get("X-r-action").contains("pong"))
    val toNextPongState = for(
      pong ← pongs.reverse.headOption;
      sessionKey ← pong.headers.get("X-r-session");
      locationHash ← pong.headers.get("X-r-location-hash")
    ) yield update(ConnectionPongState(
      pong.connectionKey,
      time,
      sessionKey,
      locationHash
    ))
    val keepAlive =
      if(pingState.time + 5000 > time) Nil
      else if(toNextPongState.nonEmpty || pongState.exists(_.time > pingState.time)) Seq(
        update(ConnectionPingState(connectionKey, time)),
        update(sseMessages.message(connectionKey, "ping", connectionKey, 1))
      )
      else Seq(update(TcpDisconnect(connectionKey)))
    pongs.map(_.request).map(delete) ++ toNextPongState ++ keepAlive
  }
}

case class FreshSSEConnection(connectionKey: String)(sseMessages: SSEMessages) extends SSEConnection {
  def wishes(time: Long): Seq[LEvent[_]] = Seq(
    update(ConnectionPingState(connectionKey, time)),
    update(sseMessages.message(connectionKey, "connect", connectionKey, 0))
  )
  def pongState: Option[ConnectionPongState] = None
}

case class ZombieSSEConnection(connectionKey: String, zombies: List[Product]) extends SSEConnection {
  def wishes(time: Long): Seq[LEvent[_]] = zombies.map(LEvent.delete)
  def pongState: Option[ConnectionPongState] = None
}


class SSEConnectionJoin(sseMessages: SSEMessages) extends Join5(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[TcpDisconnect]),
  By.srcId(classOf[ConnectionPingState]),
  By.srcId(classOf[ConnectionPongState]),
  By.srcId(classOf[HttpPostByConnection]),
  By.srcId(classOf[SSEConnection])
) {
  private def withKey(c: SSEConnection): Values[(SrcId,SSEConnection)] = List(c.connectionKey → c)
  def join(
    tcpConnected: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect],
    pingStates: Values[ConnectionPingState],
    pongStates: Values[ConnectionPongState],
    posts: Values[HttpPostByConnection]
  ) =
    if(Seq(tcpConnected,tcpDisconnects,pingStates,pongStates,posts).forall(_.isEmpty)) Nil
    else if(tcpConnected.isEmpty){ //purge
      val zombies = tcpDisconnects ++ pingStates ++ pongStates ++ posts.map(_.request)
      val key: Seq[String] = tcpDisconnects.map(_.connectionKey) ++
        pingStates.map(_.connectionKey) ++ pongStates.map(_.connectionKey) ++
        posts.map(_.connectionKey)
      withKey(ZombieSSEConnection(key.head, zombies))
    }
    else if(tcpDisconnects.nonEmpty) Nil //wait for disconnect
    else if(pingStates.isEmpty) withKey(FreshSSEConnection(
      Single(tcpConnected).connectionKey
    )(sseMessages))
    else withKey(WorkingSSEConnection(
      Single(tcpConnected).connectionKey,
      Single(pingStates),
      Single.option(pongStates),
      posts
    )(sseMessages))
  def sort(nodes: Iterable[SSEConnection]) = Single.list(nodes.toList)
}



case class HttpPostByConnection(
    connectionKey: String,
    index: Int,
    headers: Map[String,String],
    request: HttpPost
)
class HttpPostByConnectionJoin extends Join1(
  By.srcId(classOf[HttpPost]),
  By.srcId(classOf[HttpPostByConnection])
){
  def join(
    posts: Values[HttpPost]
  ) = posts.flatMap( post ⇒
    if(post.path != "/connection") Nil else {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      val index = try { headers.get("X-r-index").map(_.toInt) }
        catch { case _: Exception ⇒ None }
      val connectionKey = headers.get("X-r-connection")
      for(k ← connectionKey; i ← index) yield k → HttpPostByConnection(k,i,headers,post)
    }
  )
  def sort(nodes: Iterable[HttpPostByConnection]) = nodes.toList.sortBy(_.index)
}


// /connection X-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])