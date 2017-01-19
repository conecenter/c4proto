package ee.cone.c4gate


import java.net.URL

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

case class BranchSeedSubscription(srcId: SrcId, seed: BranchSeed, sessionKey: SrcId)

case class RichHttpPost(
  srcId: String,
  index: Long,
  headers: Map[String,String],
  request: HttpPost
)

case class BranchTaskImpl(
  branchKey: String,
  seed: Option[BranchSeed],
  product: Product,
  posts: List[RichHttpPost],
  sessionKeys: Set[SrcId],
  wasBranchResults: Values[BranchResult]
) extends BranchTask {
  //def transform(local: World): World = AlienExchangeKey.of(local)(task)(local)
  def getPosts: List[Map[String,String]] = posts.map(_.headers)

  def updateResult(newChildren: List[BranchSeed]): World ⇒ World = {
    val newBranchResult = if(newChildren.isEmpty) Nil else List(BranchResult(
      branchKey,
      seed,
      newChildren,
      sessionKeys.toList.sorted.map(Subscription)
    ))
    if(wasBranchResults == newBranchResult) identity
    else add(wasBranchResults.flatMap(delete) ::: newBranchResult.flatMap(update))
  }

  def rmPosts: World ⇒ World =
    add(posts.flatMap(post⇒delete(post.request)))
}

object CreateSeedSubscription {
  def apply(seed: BranchSeed, sessionKey: SrcId): (SrcId,BranchSeedSubscription) =
    sessionKey → BranchSeedSubscription(s"$sessionKey/${seed.hash}", seed, sessionKey)
}

class BranchOperations(registry: QAdapterRegistry) {
  def toSeed(value: Product): BranchSeed = {
    val valueAdapter = registry.byName(value.getClass.getName).asInstanceOf[ProtoAdapter[Product] with HasId]
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    BranchSeed(???, valueAdapter.id, byteString)
  }
  def decode(seed: BranchSeed): Product = {
    val valueAdapter = registry.byId(seed.valueTypeId).asInstanceOf[ProtoAdapter[Product] with HasId]
    valueAdapter.decode(seed.value.toByteArray)
  }
}

@assemble class SessionAssemble(operations: BranchOperations, host: String, file: String) extends Assemble {
  //todo reg
  type SessionKey = SrcId
  type LocationHash = SrcId

  def mapBranchSeedSubscriptionBySessionKey(
    key: SrcId,
    fromAliens: Values[FromAlien]
  ): Values[(SessionKey,BranchSeedSubscription)] =
    for(fromAlien ← fromAliens)
      yield CreateSeedSubscription(operations.toSeed(fromAlien), fromAlien.sessionKey)

  def mapBranchTaskByLocationHash(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(LocationHash,BranchTask)] =
    for(
      task ← tasks;
      url ← Option(task.product).collect{ case s:FromAlien ⇒ new URL(s.location)}
        if url.getHost == host && url.getFile == file;
      ref ← Option(url.getRef)
    ) yield url.getRef → task
}

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

  def mapBranchSeedSubscriptionBySessionKey(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(SessionKey,BranchSeedSubscription)] =
    for(
      branchResult ← branchResults;
      subscription ← branchResult.subscriptions;
      child ← branchResult.children
    ) yield CreateSeedSubscription(child, subscription.sessionKey)

  def mapBranchSeedSubscriptionByBranchKey(
    key: SrcId,
    fromAliens: Values[FromAlien],
    @by[SessionKey] seedSubscriptions: Values[BranchSeedSubscription]
  ): Values[(BranchKey,BranchSeedSubscription)] =
    for(fromAlien ← fromAliens; s ← seedSubscriptions) yield s.seed.hash → s

  def joinBranchTask(
    key: SrcId,
    @by[BranchKey] subscriptions: Values[BranchSeedSubscription],
    @by[BranchKey] posts: Values[RichHttpPost],
    wasBranchResults: Values[BranchResult]
  ): Values[(SrcId,BranchTask)] = {
    val seed = subscriptions.headOption.map(_.seed).orElse(
      Single.option(for(r←wasBranchResults;p←r.parent) yield p)
    )
    List(key → BranchTaskImpl(
      key,
      seed,
      seed.map(operations.decode).getOrElse(None),
      posts.sortBy(_.index),
      subscriptions.map(_.sessionKey).toSet,
      wasBranchResults
    ))
  }

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