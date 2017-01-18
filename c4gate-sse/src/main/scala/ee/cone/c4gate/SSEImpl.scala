package ee.cone.c4gate

import java.time.Instant
import java.time.temporal.ChronoUnit

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4gate.BranchProtocol.{BranchResult, BranchSeed, Subscription}
import ee.cone.c4gate.AlienProtocol._
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4proto.HasId

case object ToAlienPriorityKey extends WorldKey[java.lang.Long](0L)

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

case class BranchSeedSubscription(srcId: SrcId, sessionKey: SrcId, seed: BranchSeed)

case class BranchMaker(task: BranchTask) extends TxTransform {
  def transform(local: World): World = AlienExchangeKey.of(local)(task)(local)
}


//todo relocate toAlien
//todo error in view

object CreateBranchSeedSubscription {
  def apply(seed: BranchSeed, subscription: Subscription): (SrcId,BranchSeedSubscription) =
    seed.hash → BranchSeedSubscription(
      s"${subscription.sessionKey}/${seed.hash}",
      subscription.sessionKey,
      seed
    )
}

@assemble class BranchAssemble extends Assemble {
  type BranchKey = SrcId

  def joinHttpPostByBranch(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(BranchKey,RichHttpPost)] =
    for(post ← posts if post.path == "/connection") yield {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      headers("X-r-branch") → RichHttpPost(post.srcId,headers("X-r-index").toLong,headers,post)
    }

  def joinFromAlienToBranchSeedSubscription(
    key: SrcId,
    fromAliens: Values[FromAlien]
  ): Values[(BranchKey,BranchSeedSubscription)] =
    for(fromAlien ← fromAliens)
      yield CreateBranchSeedSubscription(CreateBranchSeed(fromAlien), Subscription(fromAlien.sessionKey))

  def joinBranchResultToBranchSeedSubscription(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(BranchKey,BranchSeedSubscription)] =
    for(branchResult ← branchResults; seed ← branchResult.children; subscription ← branchResult.subscriptions)
      yield CreateBranchSeedSubscription(seed,subscription)

  def joinTxTransform(
    key: SrcId,
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
        subscriptions.map(_.sessionKey).toSet,
        branchResults
      )
    else
      SimpleTxTransform((initDone ++ posts.map(_.request) ++ branchResults).flatMap(LEvent.delete)) //purge
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

next:
"X-r-vdom-branch"

?errors in embed
?bind/positioning: ref=['embed','parent',key]
*/