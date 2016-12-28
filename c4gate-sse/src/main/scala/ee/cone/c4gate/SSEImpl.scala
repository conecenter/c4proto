package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4gate.SSEProtocol.InitDone

case object SSEMessagePriorityKey extends WorldKey[Long](0)
case object SSEPingTimeKey extends WorldKey[Long](0)
case object SSEPongTimeKey extends WorldKey[Long](0)
case object SSEPongHeadersKey extends WorldKey[Map[String,String]](Map.empty)
case object SSELocationHash extends WorldKey[String]("")

case class WorkingSSEConnection(
  connectionKey: String, initDone: Boolean,
  posts: List[HttpPostByConnection]
)(config: SSEConfig) extends TxTransform with SSESend {
  def relocate(tx: WorldTx, value: String): WorldTx = {
    if(SSELocationHash.of(tx.local) == value) tx else message(tx,"relocateHash",value)
  }
  def message(tx: WorldTx, event: String, data: String): WorldTx = {
    val priority = SSEMessagePriorityKey.of(tx.local)
    val header = if(priority > 0) "" else {
      val allowOrigin =
        config.allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
      s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    }
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    val bytes = okio.ByteString.encodeUtf8(str)
    val key = UUID.randomUUID.toString
    tx.add(Seq(update(TcpWrite(key,connectionKey,bytes,priority))))
      .setLocal(SSEMessagePriorityKey,priority+1)
  }
  def transform(tx: WorldTx): WorldTx = Some(tx).map{ tx ⇒
    if(initDone) tx
    else message(tx, "connect", connectionKey)
        .add(Seq(update(InitDone(connectionKey))))
          .setLocal(SSEPingTimeKey, System.currentTimeMillis)
  }.map{ tx ⇒
    val pongs =
      posts.filter(post ⇒ post.headers.get("X-r-action").contains("pong"))
    if(pongs.isEmpty) tx
    else tx.add(pongs.map(_.request).map(delete))
      .setLocal(SSEPongTimeKey, System.currentTimeMillis)
      .setLocal(SSEPongHeadersKey, pongs.reverse.head.headers)
  }.map{ tx ⇒
    val time = System.currentTimeMillis
    val pingTime = SSEPingTimeKey.of(tx.local)
    val pongTime = SSEPongTimeKey.of(tx.local)
    if(pingTime + 5000 > time) tx
    else if(pongTime >= pingTime) // ! >=
      message(tx, "ping", connectionKey)
        .setLocal(SSEPingTimeKey, System.currentTimeMillis)
    else tx.add(Seq(update(TcpDisconnect(connectionKey))))
  }.map{ tx ⇒
    config.alienExchange(tx.setLocal(SSESendKey, Some(this)))
  }.get
}

class SSEConnectionJoin(config: SSEConfig) extends Join4(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[TcpDisconnect]),
  By.srcId(classOf[InitDone]),
  By.srcId(classOf[HttpPostByConnection]),
  By.srcId(classOf[TxTransform])
) {
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect],
    initDone: Values[InitDone],
    posts: Values[HttpPostByConnection]
  ) =
    if(Seq(tcpConnections,initDone,posts).forall(_.isEmpty)) Nil
    else if(tcpConnections.isEmpty || tcpDisconnects.nonEmpty){ //purge
      val zombies = initDone ++ posts.map(_.request)
      val key = initDone.map(_.connectionKey) ++ posts.map(_.connectionKey)
      withKey(SimpleTxTransform(key.head, zombies.map(LEvent.delete)))
    }
    else withKey(WorkingSSEConnection(
      Single(tcpConnections).connectionKey, initDone.nonEmpty, posts
    )(config))
  def sort(nodes: Iterable[TxTransform]) = Single.list(nodes.toList)
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