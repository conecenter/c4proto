package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol.{HttpPost, TcpConnection, TcpDisconnect, TcpWrite}

  /*
class SSETcpStatusHandler(
  sseMessages: SSEMessages
) {
  def mapMessage(res: WorldTx, message: LEvent[TcpConnection]): WorldTx = {
    if(!changing(mClass,res,message)) res
    val toSend = message.value.map(_⇒sseMessages.header(message.srcId)).toSeq
    res.add(toSend:_*).add(message)
  }
  def changing[M](cl: Class[M], res: WorldTx, message: LEvent[M]): Boolean =
    message.value.toList == By.srcId(cl).of(res.world).getOrElse(message.srcId,Nil).toList
}
*/





case class SSEMessages(allowOriginOption: Option[String], needWorldOffset: Long)(reducer: Reducer) extends TxTransform {
  private def header(connectionKey: String): TcpWrite = {
    val allowOrigin =
      allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    val headerString = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    message(connectionKey, headerString, 0)
  }
  def message(connectionKey: String, event: String, data: String, priority: Long): TcpWrite = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    message(connectionKey, s"event: $event\ndata: $escapedData\n\n", priority)
  }
  private def message(connectionKey: String, data: String, priority: Long): TcpWrite = {
    val bytes = okio.ByteString.encodeUtf8(data)
    val key = UUID.randomUUID.toString
    TcpWrite(key,connectionKey,bytes,priority)
  }
  def transform(tx: WorldTx): WorldTx = {
    val time = System.currentTimeMillis()
    Some(tx).map{ tx ⇒
      val connections = By.srcId(classOf[SSEConnection]).of(tx.world)
      val events = connections.values.flatten.flatMap{ conn ⇒
        conn.state match{
          case None ⇒
            LEvent.update(SSEConnectionState(conn.connectionKey,time,0)) ::
              LEvent.update(header(conn.connectionKey)) ::
              LEvent.update(message("connect",conn.connectionKey,1)) ::
              Nil
          case Some(state@SSEConnectionState(_,pingTime,pongTime)) ⇒
            if(Math.max(pingTime,pongTime) + 5000 > time) Nil
            else if(pingTime < pongTime)
              LEvent.update(state.copy(pingTime=time)) ::
                LEvent.update(message("ping",conn.connectionKey,2)) ::
                Nil
            else
              LEvent.update(TcpDisconnect(conn.connectionKey)) ::
                LEvent.delete(state) ::
                Nil
        }
      }.toSeq
      tx.add(events:_*)
    }.map { tx ⇒
      val connections = By.srcId(classOf[SSEConnection]).of(tx.world)
      val events = connections.values.flatten.flatMap { conn ⇒
        val reqTime = conn.posts.map(_.request.time).max
        if(conn.state.get.pongTime < reqTime)
          LEvent.update(conn.state.get.copy(pongTime=reqTime)) :: Nil else Nil
      }.toSeq
      tx.add(events:_*)
    }.get
  }
}



case class SSEConnectionState(connectionKey: String, pingTime: Long, pongTime: Long)
case class SSEConnection(connectionKey: String, state: Option[SSEConnectionState], posts: List[HttpPostByConnection])

object Single {
  def apply[C](l: List[C]): C = if(l.tail.nonEmpty) throw new Exception else l.head
  def option[C](l: List[C]): Option[C] = if(l.isEmpty) None else Option(apply(l))
  def list[C](l: List[C]): List[C] = if(l.isEmpty || l.tail.isEmpty) l else throw new Exception
}


class SSEConnectionJoin extends Join3(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[SSEConnectionState]),
  By.srcId(classOf[HttpPostByConnection]),
  By.srcId(classOf[SSEConnection])
) {
  def join(
    tcpConnected: Values[TcpConnection],
    states: Values[SSEConnectionState],
    posts: Values[HttpPostByConnection]
  ) = tcpConnected.map(_.connectionKey).map(c ⇒
    c → SSEConnection(c, Single.option(states), posts)
  )
  def sort(nodes: Iterable[SSEConnection]) = Single.list(nodes.toList)
}

case class HttpPostByConnection(connectionKey: String, headers: Map[String,String], request: HttpPost)
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
      headers.get("X-r-connection").map(k ⇒ k → HttpPostByConnection(k,headers,post))
    }
  )
  def sort(nodes: Iterable[HttpPostByConnection]) = nodes.toList.sortBy(_.request.time)
}


// /connection X-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])