package ee.cone.c4gate


import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4gate.BranchProtocol.BranchResult
import ee.cone.c4gate.AlienProtocol._
import ee.cone.c4gate.BranchTypes._
import ee.cone.c4gate.HttpProtocol.HttpPost


//todo relocate toAlien
//todo error in view
//todo checkUpdate()?

case object ToAlienPriorityKey extends WorldKey[java.lang.Long](0L)

case class RichHttpPost(
  srcId: String,
  index: Long,
  headers: Map[String,String],
  request: HttpPost
)


trait BranchHandler extends Product {
  def exchange: (String⇒String) ⇒ World ⇒ World
  def seeds: World ⇒ List[BranchResult]
}

case class VoidBranchHandler() extends BranchHandler {
  def exchange: (String⇒String) ⇒ World ⇒ World = _⇒identity
  def seeds: World ⇒ List[BranchResult] = _⇒Nil
}

case class BranchSenderImpl(branchKey: String) extends BranchSender {
  def send: (String,String,String) ⇒ World ⇒ World = (sessionKey,event,data) ⇒ local ⇒
    add(update(ToAlienWrite(UUID.randomUUID.toString,sessionKey,event,data,ToAlienPriorityKey.of(local))))
      .andThen(ToAlienPriorityKey.modify(_+1))(local)

  def sessionKeys: World ⇒ Set[SrcId] = local ⇒ {
    val world = TxKey.of(local).world
    val relIndex = By.srcId(classOf[BranchRel]).of(world)
    def find(branchKey: SrcId): List[SrcId] =
      relIndex.getOrElse(branchKey,Nil).flatMap(rel⇒
        if(rel.parentIsSession) rel.parentSrcId :: Nil else find(rel.parentSrcId)
      )
    find(branchKey).toSet
  }
}

case class BranchTaskImpl(
  branchKey: String,
  seed: Option[BranchResult],
  product: Product,
  posts: List[RichHttpPost],
  wasBranchResults: Values[BranchResult],
  handler: BranchHandler = VoidBranchHandler()
) extends BranchTask {
  def sender: BranchSender = BranchSenderImpl(branchKey)
  def withHandler(handler: BranchHandler): BranchTask = copy(handler=handler)
  def updateResult(newChildren: List[BranchResult]): World ⇒ World = {
    val newBranchResult = if(newChildren.isEmpty) Nil else List(seed.get.copy(children = newChildren))
    if(wasBranchResults == newBranchResult) identity
    else add(wasBranchResults.flatMap(delete) ::: newBranchResult.flatMap(update))
  }
  def rmPosts: World ⇒ World =
    add(posts.flatMap(post⇒delete(post.request)))
  def transform(local: World): World =
    if(posts.isEmpty) handler.exchange(_⇒"")(local)
    else {
      (identity[World] _ /: (for(m ← posts) yield handler.exchange(m.headers.getOrElse(_,""))))((a,b)⇒ a andThen b)
        .andThen(local ⇒ updateResult(handler.seeds(local))(local))
        .andThen(rmPosts)(local)
    }
}

class BranchOperations(registry: QAdapterRegistry) {
  private def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def toSeed(value: Product): BranchResult = {
    val valueAdapter = registry.byName(value.getClass.getName)
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    val id = UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes)
    BranchResult(id.toString, valueAdapter.id, byteString, Nil)
  }
  def decode(seed: BranchResult): Product =
    registry.byId(seed.valueTypeId).decode(seed.value.toByteArray)
}

object BranchTypes {
  type BranchKey = SrcId
  type LocationHash = SrcId
}

case class BranchRel(srcId: SrcId, seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean)
object BranchRel {
  def apply(seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel) =
    seed.hash → BranchRel(s"${seed.hash}/$parentSrcId",seed,parentSrcId,parentIsSession)
}

@assemble class FromAlienBranchAssemble(operations: BranchOperations, host: String, file: String) extends Assemble {

  // more rich session may be joined
  //todo reg
  def fromAliensToSeeds(
      key: SrcId,
      fromAliens: Values[FromAlien]
  ): Values[(BranchKey, BranchRel)] =
  for (fromAlien ← fromAliens; child ← Option(operations.toSeed(fromAlien)))
    yield BranchRel(child, fromAlien.sessionKey, parentIsSession = true)
  def mapBranchTaskByLocationHash(
      key: SrcId,
      tasks: Values[BranchTask]
  ): Values[(LocationHash, BranchTask)] =
    for (
      task ← tasks;
      url ← Option(task.product).collect { case s: FromAlien ⇒ new URL(s.location) }
      if url.getHost == host && url.getFile == file;
      ref ← Option(url.getRef)
    ) yield url.getRef → task
}

@assemble class PurgeBranchAssemble extends Assemble {//todo reg
  def purgeBranches(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId,TxTransform)] =
    for(task ← tasks if task.product == None) yield key → task
}

@assemble class BranchAssemble(operations: BranchOperations) extends Assemble {
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

  def mapBranchSeedsByChild(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(BranchKey,BranchRel)] =
    for(branchResult ← branchResults; child ← branchResult.children)
      yield BranchRel(child, branchResult.hash, parentIsSession=false)

  def joinBranchTask(
    key: SrcId,
    wasBranchResults: Values[BranchResult],
    @by[BranchKey] seeds: Values[BranchRel],
    @by[BranchKey] posts: Values[RichHttpPost]
  ): Values[(SrcId,BranchTask)] = {
    val seed = seeds.headOption.map(_.seed)
      .orElse(if(posts.nonEmpty) wasBranchResults.headOption else None)
    List(key → BranchTaskImpl(
      key,
      seed,
      seed.map(operations.decode).getOrElse(None),
      posts.sortBy(_.index),
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