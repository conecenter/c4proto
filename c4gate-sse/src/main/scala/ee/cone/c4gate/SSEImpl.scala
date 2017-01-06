package ee.cone.c4gate

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4gate.InternetProtocol._

case object SSEMessagePriorityKey extends WorldKey[java.lang.Long](0L)
case object SSEPingTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSEPongTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSELocationHash extends WorldKey[String]("")



case class WorkingSSEConnection(
  connectionKey: String, initDone: Boolean,
  posts: List[HttpPostByConnection]
) extends TxTransform /*with SSESend*/ {
  /*def relocate(tx: WorldTx, value: String): WorldTx = {
    if(SSELocationHash.of(tx.local) == value) tx else message(tx,"relocateHash",value)
  }*/
  private def message(event: String, data: String)(local: World): World = {
    val priority = SSEMessagePriorityKey.of(local)
    val header = if(priority > 0) "" else {
      val allowOrigin =
        AllowOriginKey.of(local).map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
      s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    }
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    val bytes = okio.ByteString.encodeUtf8(str)
    val key = UUID.randomUUID.toString
    Some(local)
      .map(SSEMessagePriorityKey.modify(_+1))
      .map(add(update(TcpWrite(key,connectionKey,bytes,priority)))).get
  }

  private def toAlien(local: World): World = {
    val (nLocal,messages) = ToAlienKey.of(local)(local)
    (nLocal /: messages) { (local, msg) ⇒ msg match {
      case (event,data) ⇒ message(event,data)(local)
    }}
  }

  private def pingAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPingTimeKey.of(local), Instant.now)
  private def pongAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPongTimeKey.of(local), Instant.now)

  private def needInit(local: World): World = if(initDone) local else Some(local)
    .map(message("connect", s"$connectionKey ${PostURLKey.of(local).get}"))
    .map(add(update(AppLevelInitDone(connectionKey))))
    .map(SSEPingTimeKey.modify(_⇒Instant.now)).get

  private def needPing(local: World): World =
    if(pingAge(local) < 5) local else Some(local)
      .map(message("ping", connectionKey))
      .map(SSEPingTimeKey.modify(_⇒Instant.now)).get

  private def handlePosts(local: World): World =
    (Option(local) /: posts) { (localOpt, post) ⇒ localOpt
        .map(FromAlienKey.of(local)(post.headers.get)).map(toAlien)
        .map(add(delete(post.request)))
        .map(SSEPongTimeKey.modify(_⇒Instant.now))
    }.get

  private def disconnect(local: World): World =
    add(update(TcpDisconnect(connectionKey)))(local)

  def transform(local: World): World = Some(local)
    .map(needInit)
    .map(needPing)
    .map(local ⇒
      if(ErrorKey.of(local).nonEmpty) disconnect(local)
      else if(posts.nonEmpty) handlePosts(local)
      else if(pingAge(local) < 2 || pongAge(local) < 5) toAlien(local)
      else disconnect(local)
    ).get
}

@assemble class SSEAssemble extends Assemble {
  def joinHttpPostByConnection(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId,HttpPostByConnection)] = posts.flatMap( post ⇒
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
  def joinTxTransform(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect],
    initDone: Values[AppLevelInitDone],
    posts: Values[HttpPostByConnection]
  ): Values[(SrcId,TxTransform)] = List(key → (
    if(tcpConnections.isEmpty || tcpDisconnects.nonEmpty) //purge
      SimpleTxTransform((initDone ++ posts.map(_.request)).flatMap(LEvent.delete))
    else WorkingSSEConnection(key, initDone.nonEmpty, posts.sortBy(_.index))
  ))
}


// /connection X-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])

/* embed plan:
TcpWrite to many conns
dispatch to service by sse.js
posts to connections and sseUI-s
vdom emb host/guest
subscr? cli or serv
RootViewResult(...,subviews)
/
@ FromAlien(sessionKey,locationHash)
/
@@ Embed(headers)
@ UIResult(srcId {connectionKey|embedHash}, needChildEmbeds, connectionKeys)
/
TxTr(embedHash,embed,connections)

 */