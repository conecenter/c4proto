package ee.cone.c4gate

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._

case object SSEMessagePriorityKey extends WorldKey[java.lang.Long](0L)
case object SSEPingTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSEPongTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSELocationHash extends WorldKey[String]("")

case class WorkingSSEConnection(
  connectionKey: String, initDone: Boolean,
  posts: List[HttpPostByConnection]
)(sseUI: SSEui) extends TxTransform /*with SSESend*/ {
  /*def relocate(tx: WorldTx, value: String): WorldTx = {
    if(SSELocationHash.of(tx.local) == value) tx else message(tx,"relocateHash",value)
  }*/
  private def message(event: String, data: String)(local: World): World = {
    val priority = SSEMessagePriorityKey.of(local)
    val header = if(priority > 0) "" else {
      val allowOrigin =
        sseUI.allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
      s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    }
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    val bytes = okio.ByteString.encodeUtf8(str)
    val key = UUID.randomUUID.toString
    Some(local)
      .map(SSEMessagePriorityKey.transform(_+1))
      .map(add(Seq(update(TcpWrite(key,connectionKey,bytes,priority))))).get
  }

  private def toAlien(local: World): World = {
    val (nLocal,messages) = sseUI.toAlien(local)
    (nLocal /: messages) { (local, msg) ⇒ msg match {
      case (event,data) ⇒ message(event,data)(local)
    }}
  }

  private def pingAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPingTimeKey.of(local), Instant.now)
  private def pongAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPongTimeKey.of(local), Instant.now)

  private def needInit(local: World): World = if(initDone) local else Some(local)
    .map(message("connect", connectionKey))
    .map(add(Seq(update(AppLevelInitDone(connectionKey)))))
    .map(SSEPingTimeKey.transform(_⇒Instant.now)).get

  private def needPing(local: World): World =
    if(pingAge(local) < 5) local else Some(local)
      .map(message("ping", connectionKey))
      .map(SSEPingTimeKey.transform(_⇒Instant.now)).get

  private def handlePosts(local: World): World =
    (Option(local) /: posts) { (localOpt, post) ⇒ localOpt
        .map(sseUI.fromAlien(post.headers.get)).map(toAlien)
        .map(add(Seq(delete(post.request))))
        .map(SSEPongTimeKey.transform(_⇒Instant.now))
    }.get

  def transform(local: World): World = Some(local)
    .map(needInit)
    .map(needPing)
    .map(local ⇒
      if(posts.nonEmpty) handlePosts(local)
      else if(pingAge(local) < 2 || pongAge(local) < 5) toAlien(local)
      else add(Seq(update(TcpDisconnect(connectionKey))))(local)
    ).get
}

class SSEConnectionJoin(sseUI: SSEui) extends Join4(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[TcpDisconnect]),
  By.srcId(classOf[AppLevelInitDone]),
  By.srcId(classOf[HttpPostByConnection]),
  By.srcId(classOf[TxTransform])
) {
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect],
    initDone: Values[AppLevelInitDone],
    posts: Values[HttpPostByConnection]
  ) =
    if(tcpConnections.isEmpty || tcpDisconnects.nonEmpty){ //purge
      val zombies: List[Product] = initDone ++ posts.map(_.request)
      val key = initDone.map(_.connectionKey) ++ posts.map(_.connectionKey)
      withKey[Product with TxTransform](SimpleTxTransform(key.head, zombies.map(LEvent.delete)))
    }
    else withKey[Product with TxTransform](WorkingSSEConnection(
      Single(tcpConnections).connectionKey, initDone.nonEmpty, posts
    )(sseUI))
  def sort(nodes: Iterable[TxTransform]) = Single.list(nodes.toList)
}


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