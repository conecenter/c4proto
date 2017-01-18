package ee.cone.c4gate


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

//todo relocate toAlien
//todo error in view
//todo checkUpdate()?

case object ToAlienPriorityKey extends WorldKey[java.lang.Long](0L)

case class BranchSeedSubscription(srcId: SrcId, seed: DecodedBranchSeed, sessionKey: SrcId)

//case class BranchSeedSubscriptions(hash: SrcId, seed: DecodedBranchSeed, subscriptions: Values[Subscription])

trait BranchMaker {
  def updateResult(newBranchResult: Option[BranchResult]): World ⇒ World
  def rmPosts: World ⇒ World
}

case class BranchMakerImpl(task: BranchTask) extends TxTransform with BranchMaker {
  def transform(local: World): World = AlienExchangeKey.of(local)(task)(local)
  def updateResult(newBranchResult: Option[BranchResult]): World ⇒ World =
    if(task.branchResults == newBranchResult.toList) identity
    else add(task.branchResults.flatMap(delete) ::: newBranchResult.toList.flatMap(update))
  def rmPosts: World ⇒ World =
    add(task.posts.flatMap(post⇒delete(post.request)))
}

case class DecodedBranchSeed(hash: SrcId, value: Product)

case class DecodedBranchResult(
    hash: SrcId,
    parent: DecodedBranchSeed,
    children: List[DecodedBranchSeed],
    subscriptions: List[Subscription]
)

object CreateSeedSubscription {
  def apply(seed: DecodedBranchSeed, subscription: Subscription): BranchSeedSubscription =
    BranchSeedSubscription(
      s"${subscription.sessionKey}/${seed.hash}", seed, subscription.sessionKey
    )
}
class BranchOperations(registry: QAdapterRegistry) {
  def toSeed(value: Product): BranchSeed = {
    val valueAdapter = registry.byName(value.getClass.getName).asInstanceOf[ProtoAdapter[Product] with HasId]
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    BranchSeed(???, valueAdapter.id, byteString)
  }
  def decode(seed: BranchSeed): DecodedBranchSeed = {
    val valueAdapter = registry.byId(seed.valueTypeId).asInstanceOf[ProtoAdapter[Product] with HasId]
    DecodedBranchSeed(seed.hash, valueAdapter.decode(seed.value.toByteArray))
  }
  def decode(result: BranchResult): DecodedBranchResult =
    DecodedBranchResult(
      result.hash,
      result.parent.map(decode).get,
      result.children.map(decode),
      result.subscriptions
    )
}

/*
case class ProductProtoAdapter(className: SrcId, id: Long)(val adapter: ProtoAdapter[Product])
def createWorld: World ⇒ World = setupAssembler andThen setupAdapters
private def setupAdapters =
    By.srcId(classOf[ProductProtoAdapter]).set(protocols.flatMap(_.adapters).map(adapter⇒
      adapter.className →
        ProductProtoAdapter(adapter.id,adapter.className)(adapter.asInstanceOf[ProtoAdapter[Product]]) :: Nil
    ).toMap)


@assemble class AdapterAssemble extends Assemble {
  type AnnotationId = Long
  def joinProductProtoAdapterByAnnotationId(
    key: SrcId,
    adapters: Values[ProductProtoAdapter]
  ): Values[(AnnotationId,ProductProtoAdapter)] =
    adapters.map(a⇒a.id→a)
}

*/

@assemble class BranchAssemble(operations: BranchOperations) extends Assemble { //todo reg
  type BranchKey = SrcId
  type SessionKey = SrcId

  def mapHttpPostByBranch(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(BranchKey,RichHttpPost)] =
    for(post ← posts if post.path == "/connection") yield {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      headers("X-r-branch") → RichHttpPost(post.srcId,headers("X-r-index").toLong,headers,post)
    }

  def mapDecodedBranchResultBySessionKey(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(SessionKey,BranchSeedSubscription)] =
    for(
      branchResult ← branchResults.map(operations.decode);
      subscription ← branchResult.subscriptions;
      child ← branchResult.children
    ) yield subscription.sessionKey → CreateSeedSubscription(child, subscription)

  def joinBranchSeedSubscriptionBySeed(
    key: SrcId,
    fromAliens: Values[FromAlien],
    @by[SessionKey] seedSubscriptions: Values[BranchSeedSubscription]
  ): Values[(BranchKey,BranchSeedSubscription)] =
    fromAliens.flatMap { fromAlien ⇒
      val seed = operations.decode(operations.toSeed(fromAlien))
      CreateSeedSubscription(seed, Subscription(fromAlien.sessionKey)) :: seedSubscriptions
    }.map( s ⇒ s.seed.hash → s )

  def joinTxTransform(
    key: SrcId,
    @by[BranchKey] subscriptions: Values[BranchSeedSubscription],
    @by[BranchKey] posts: Values[RichHttpPost],
    branchResults: Values[BranchResult]
  ): Values[(SrcId,TxTransform)] = List(key → BranchMakerImpl(BranchTask(
    key,
    subscriptions.headOption.map(_.seed),
    posts.sortBy(_.index),
    subscriptions.map(_.sessionKey).toSet,
    branchResults.map(operations.decode)
  )))

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