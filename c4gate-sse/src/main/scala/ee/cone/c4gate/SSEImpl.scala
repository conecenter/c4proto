package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4gate.SSEProtocol.{ConnectionPingState, ConnectionPongState}
import ee.cone.c4proto.{Id, Protocol, protocol}

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


@protocol object SSEProtocol extends Protocol {
  @Id(0x0030) case class ConnectionPingState(
      @Id(0x0031) connectionKey: String,
      @Id(0x0032) time: Long
  )
  @Id(0x0033) case class ConnectionPongState(
      @Id(0x0031) connectionKey: String,
      @Id(0x0032) time: Long,
      @Id(0x0034) sessionKey: String,
      @Id(0x0035) locationHash: String
  )
}

/*
case class SSEConnection(
    connectionKey: String,
    connection: TcpConnection,
    pingState: ConnectionPingState,
    pongState: ConnectionPongState,
    posts: List[HttpPostByConnection]
)*/
case class SSEConnection(connectionKey: String, state: Option[ConnectionPongState])(
    val wishes: Long⇒Seq[LEvent[_]]
)

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
  ) = {
    if(Seq(tcpConnected,tcpDisconnects,pingStates,pongStates,posts).forall(_.isEmpty)) Nil
    else if(tcpConnected.isEmpty){ //purge
      val zombies = tcpDisconnects ++ pingStates ++ pongStates ++ posts.map(_.request)
      val key: Seq[String] = tcpDisconnects.map(_.connectionKey) ++
        pingStates.map(_.connectionKey) ++ pongStates.map(_.connectionKey) ++
        posts.map(_.connectionKey)
      withKey(SSEConnection(key.head,None)(_⇒zombies.map(LEvent.delete)))
    }
    else if(tcpDisconnects.nonEmpty) Nil //wait for disconnect
    else if(pingStates.isEmpty){ //init
      val k = Single(tcpConnected).connectionKey
      withKey(SSEConnection(k,None)(
        time⇒Seq(ConnectionPingState(k,time),sseMessages.message(k,"connect",k,0)).map(LEvent.update)
      ))
    }
    else {
      val k = Single(tcpConnected).connectionKey
      val pongState = Single.option(pongStates)
      val wishes = (time:Long) ⇒ {
        val pongs = posts.filter(post ⇒ post.headers.get("X-r-action").contains("pong"))
        val nextState = for(
          pong ← pongs.reverse.headOption;
          sessionKey ← pong.headers.get("X-r-session");
          locationHash ← pong.headers.get("X-r-location-hash")
        ) yield ConnectionPongState(pong.connectionKey,time,sessionKey,locationHash)
        //
        val pingTime: Long = Single(pingStates).time
        val keepAlive = if(pingTime + 5000 > time) Nil
          else if(nextState.nonEmpty || pongState.exists(_.time > pingTime))
            Seq(ConnectionPingState(k,time), sseMessages.message(k,"ping",k,1))
          else Seq(TcpDisconnect(k))
        //
        pongs.map(LEvent.delete) ++ nextState.map(LEvent.update) ++ keepAlive.map(LEvent.update)
      }
      withKey(SSEConnection(k,pongState)(wishes))
    }
  }

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