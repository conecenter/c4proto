package ee.cone.c4gate

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4gate.BranchProtocol.{BranchResult, BranchSeed, Subscription}
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4proto
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}

case object SSEMessagePriorityKey extends WorldKey[java.lang.Long](0L)
case object SSEPingTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSEPongTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSELocationHash extends WorldKey[String]("")

case class HttpPostByConnection(
    connectionKey: String,
    index: Long,
    headers: Map[String,String],
    request: HttpPost
)

object SSEMessage {
  def connect(connectionKey: String, data: String): World ⇒ World = local ⇒ {
    val allowOrigin =
      AllowOriginKey.of(local).map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    val header = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    send(connectionKey, "connect", data, header, -1)(local)
  }
  def message(connectionKey: String, event: String, data: String): World ⇒ World = local ⇒
    send(connectionKey, event, data, "", SSEMessagePriorityKey.of(local))(local)
  private def send(connectionKey: String, event: String, data: String, header: String, priority: Long): World ⇒ World = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    val bytes = okio.ByteString.encodeUtf8(str)
    val key = UUID.randomUUID.toString
    SSEMessagePriorityKey.set(priority+1)
      .andThen(add(update(TcpWrite(key,connectionKey,bytes,priority))))
  }
}

case class WorkingSSEConnection(
  connectionKey: String, initDone: Boolean,
  posts: List[HttpPostByConnection]
) extends TxTransform /*with SSESend*/ {
  /*def relocate(tx: WorldTx, value: String): WorldTx = {
    if(SSELocationHash.of(tx.local) == value) tx else message(tx,"relocateHash",value)
  }*/
  import SSEMessage._

  private def pingAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPingTimeKey.of(local), Instant.now)
  private def pongAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPongTimeKey.of(local), Instant.now)

  private def needInit(local: World): World = if(initDone) local
  else connect(connectionKey, s"$connectionKey ${PostURLKey.of(local).get}")
    .andThen(add(update(AppLevelInitDone(connectionKey))))
    .andThen(SSEPingTimeKey.set(Instant.now))(local)

  private def needPing(local: World): World =
    if(pingAge(local) < 5) local
    else message(connectionKey,"ping", connectionKey)
      .andThen(SSEPingTimeKey.set(Instant.now))(local)

  private def handlePosts(local: World): World =
    (local /: posts) { (local, post) ⇒
      //FromAlienKey.of(local)(post.headers.get).andThen(toAlien)
      relocate
        .andThen(add(delete(post.request)))
        .andThen(SSEPongTimeKey.set(Instant.now))(local)
    }

  private def disconnect(local: World): World =
    add(update(TcpDisconnect(connectionKey)))(local)

  def transform(local: World): World = Some(local)
    .map(needInit)
    .map(needPing)
    .map(local ⇒
      if(ErrorKey.of(local).nonEmpty) disconnect(local)
      else if(posts.nonEmpty) handlePosts(local)
      else if(pingAge(local) < 2 || pongAge(local) < 5) local
      else disconnect(local)
    ).get
}

////

object CreateBranchSeed {
  def apply(value: Product): BranchSeed = {
    val registry: QAdapterRegistry = ???
    val valueAdapter = registry.byName(value.getClass.getName).asInstanceOf[ProtoAdapter[Product] with HasId]
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    BranchSeed(???,valueAdapter.id, byteString)
  }
}

case class BranchSeedSubscription(
  srcId: SrcId,
  connectionKey: SrcId,
  seed: BranchSeed
)

case class BranchMaker(seed: BranchSeed, connectionKeys: Set[SrcId]) extends TxTransform {
  def transform(local: World): World = ???
}

@assemble class BranchAssemble extends Assemble { //todo reg
  type SeedHash = SrcId
  def joinRichBranchSeed(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(SeedHash,BranchSeedSubscription)] =
    for(branchResult ← branchResults; seed ← branchResult.children; subscription ← branchResult.subscriptions)
      yield seed.hash → BranchSeedSubscription(s"${subscription.connectionKey}/${seed.hash}", subscription.connectionKey, seed)
  def joinTxTransform(
    key: SrcId,
    @by[SeedHash] subscriptions: Values[BranchSeedSubscription]
  ): Values[(SrcId,TxTransform)] =
    List(key → BranchMaker(subscriptions.head.seed, subscriptions.map(_.connectionKey).toSet))

  //: Values[(SrcId,TxTransform)] = ???
}

@protocol object BranchProtocol extends c4proto.Protocol { //todo reg
  case class Subscription(
    @Id(???) connectionKey: SrcId
  )
  case class BranchSeed(
    @Id(???) hash: String,
    @Id(???) valueTypeId: Long,
    @Id(???) value: okio.ByteString
  )
  @Id(???) case class BranchResult(
    @Id(???) srcId: String,
    @Id(???) children: List[BranchSeed],
    @Id(???) subscriptions: List[Subscription]
  )

  @Id(???) case class FromAlien(
    @Id(???) connectionKey: SrcId,
    @Id(???) sessionKey: SrcId,
    @Id(???) locationSearch: String,
    @Id(???) locationHash: String
  )

}


/*
"X-r-session": sessionKey(never),
"X-r-location-search": location.search.substr(1),
"X-r-location-hash": location.hash.substr(1)
*/
/*
case class WorkingSSEConnection(
  connectionKey: String, initDone: Boolean,
  posts: List[HttpPostByConnection]
) extends TxTransform /*with SSESend*/ {
  /*def relocate(tx: WorldTx, value: String): WorldTx = {
    if(SSELocationHash.of(tx.local) == value) tx else message(tx,"relocateHash",value)
  }*/
  import SSEMessage._

  private def toAlien(local: World): World = {
    val (nLocal,messages) = ToAlienKey.of(local)(local)
    (nLocal /: messages) { (local, msg) ⇒ msg match {
      case (event,data) ⇒ message(connectionKey,event,data)(local)
    }}
  }

  private def pingAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPingTimeKey.of(local), Instant.now)
  private def pongAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPongTimeKey.of(local), Instant.now)

  private def needInit(local: World): World = if(initDone) local
    else connect(connectionKey, s"$connectionKey ${PostURLKey.of(local).get}")
    .andThen(add(update(AppLevelInitDone(connectionKey))))
    .andThen(SSEPingTimeKey.set(Instant.now))(local)

  private def needPing(local: World): World =
    if(pingAge(local) < 5) local
    else message(connectionKey,"ping", connectionKey)
      .andThen(SSEPingTimeKey.set(Instant.now))(local)

  private def handlePosts(local: World): World =
    (local /: posts) { (local, post) ⇒
        FromAlienKey.of(local)(post.headers.get).andThen(toAlien)
        .andThen(add(delete(post.request)))
        .andThen(SSEPongTimeKey.set(Instant.now))(local)
    }

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
*/


object HttpPostParse {
  def apply(post: HttpPost): Option[(String,Long,Map[String,String])] =
    if(post.path != "/connection") None else {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      for(action ← headers.get("X-r-action"))
        yield (action, headers("X-r-index").toLong, headers)
    }
}

@assemble class SSEAssemble extends Assemble {
  def joinHttpPostByConnection(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId,HttpPostByConnection)] = for(
    post ← posts;
    (action,index,headers) ← HttpPostParse(post) if action == "pong"
  ) yield {
    val k = headers("X-r-connection")
    //val hash = headers("X-r-location-hash")
    k → HttpPostByConnection(k,index,headers,post)
  }

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

object NoProxySSEConfig extends InitLocal {
  def initLocal: World ⇒ World =
    AllowOriginKey.set(Option("*"))
    .andThen(PostURLKey.set(Option("/connection")))
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

next:
"X-r-vdom-branch"

?errors in embed
?bind/positioning: ref=['embed','parent',key]
*/