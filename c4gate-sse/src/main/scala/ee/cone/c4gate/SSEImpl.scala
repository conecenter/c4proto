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
import ee.cone.c4gate.BranchProtocol.{BranchResult, BranchSeed, FromAlien, Subscription}
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4proto
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}



case object SSEMessagePriorityKey extends WorldKey[java.lang.Long](0L)
case object SSEPingTimeKey extends WorldKey[Instant](Instant.MIN)
case object SSEPongTimeKey extends WorldKey[Instant](Instant.MIN)
//case object SSELocationHash extends WorldKey[String]("")






case class RichHttpPosts(posts: Values[RichHttpPost]) { //todo api
  def remove: World ⇒ World = add(posts.flatMap(post⇒delete(post.request)))
}

object UpdateBranchResult { //todo api
  def apply(wasBranchResults: Values[BranchResult], newBranchResult: BranchResult): World ⇒ World =
    if(wasBranchResults == List(newBranchResult)) identity else add(update(newBranchResult)) //todo checkUpdate()?
}

object CreateBranchSeed { //todo api
  def apply(value: Product): BranchSeed = {
    val registry: QAdapterRegistry = ???
    val valueAdapter = registry.byName(value.getClass.getName).asInstanceOf[ProtoAdapter[Product] with HasId]
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    BranchSeed(???, valueAdapter.id, byteString)
  }
}


case class WorkingSSEConnection(
  connectionKey: String, initDone: Boolean,
  posts: List[RichHttpPost],
  branchResults: Values[BranchResult]
) extends TxTransform /*with SSESend*/ {
  import SSEMessageImpl._

  private def pingAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPingTimeKey.of(local), Instant.now)
  private def pongAge(local: World): Long =
    ChronoUnit.SECONDS.between(SSEPongTimeKey.of(local), Instant.now)
  private def pingAgeUpdate: World ⇒ World = SSEPingTimeKey.set(Instant.now)
  private def pongAgeUpdate: World ⇒ World = SSEPongTimeKey.set(Instant.now)

  private def needInit(local: World): World = if(initDone) local
  else connect(connectionKey, s"$connectionKey ${PostURLKey.of(local).get}")
    .andThen(add(update(AppLevelInitDone(connectionKey))))
    .andThen(pingAgeUpdate)(local)

  private def needPing(local: World): World =
    if(pingAge(local) < 5) local
    else message(connectionKey, "ping", connectionKey).andThen(pingAgeUpdate)(local)

  private def disconnect: World ⇒ World = add(update(TcpDisconnect(connectionKey)))
  private def relocate: World ⇒ World = {
    val headers = posts.last.headers
    val seed = CreateBranchSeed(FromAlien(
      connectionKey,
      headers("X-r-session"),
      headers("X-r-location-search"),
      headers("X-r-location-hash")
    ))
    val res = BranchResult(connectionKey, List(seed), List(Subscription(connectionKey)))
    UpdateBranchResult(branchResults, res)
    //todo relocate toAlien
  }

  def transform(local: World): World = Some(local)
    .map(needInit)
    .map(needPing)
    .map(local ⇒
      if(ErrorKey.of(local).nonEmpty) disconnect(local)
      else if(posts.nonEmpty)
        relocate.andThen(RichHttpPosts(posts).remove).andThen(pongAgeUpdate)(local)
      else if(pingAge(local) > 2 && pongAge(local) > 5) disconnect(local)
      else local
    ).get
}

case class BranchSeedSubscription(
  srcId: SrcId,
  connectionKey: SrcId,
  seed: BranchSeed
)

case class BranchMaker(task: BranchTask) extends TxTransform {
  def transform(local: World): World = AlienExchangeKey.of(local)(task)(local)
}



//todo error in view

@assemble class SSEAssemble extends Assemble {
  type BranchKey = SrcId

  def joinHttpPostByBranch(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(BranchKey,RichHttpPost)] =
    for(post ← posts if post.path == "/connection") yield {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      val k = headers.getOrElse("X-r-branch", headers("X-r-connection"))
      k → RichHttpPost(post.srcId,headers("X-r-index").toLong,headers,post)
    }

  def joinBranchSeedSubscription(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(BranchKey,BranchSeedSubscription)] =
    for(branchResult ← branchResults; seed ← branchResult.children; subscription ← branchResult.subscriptions)
      yield seed.hash → BranchSeedSubscription(
        s"${subscription.connectionKey}/${seed.hash}",
        subscription.connectionKey,
        seed
      )

  def joinTxTransform(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect],
    initDone: Values[AppLevelInitDone],
    @by[BranchKey] posts: Values[RichHttpPost],
    @by[BranchKey] subscriptions: Values[BranchSeedSubscription],
    branchResults: Values[BranchResult]
  ): Values[(SrcId,TxTransform)] = List(key → (
    if(tcpConnections.nonEmpty && tcpDisconnects.isEmpty)
      WorkingSSEConnection(key, initDone.nonEmpty, posts.sortBy(_.index), branchResults)
    else if(subscriptions.nonEmpty)
      BranchMaker(
        key,
        subscriptions.head.seed,
        posts.sortBy(_.index),
        subscriptions.map(_.connectionKey).toSet,
        branchResults
      )
    else
      SimpleTxTransform((initDone ++ posts.map(_.request) ++ branchResults).flatMap(LEvent.delete)) //purge
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