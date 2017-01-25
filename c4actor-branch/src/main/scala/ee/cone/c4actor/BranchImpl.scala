
package ee.cone.c4actor

import java.nio.ByteBuffer
import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4actor.BranchTypes._

import scala.collection.immutable.Queue

case class VoidBranchHandler() extends BranchHandler {
  def exchange: (String⇒String) ⇒ World ⇒ World = _⇒identity
  def seeds: World ⇒ List[BranchResult] = _⇒Nil
}

case class BranchTaskImpl(branchKey: String, seeds: Values[BranchRel], product: Product) extends BranchTask {
  def sessionKeys: World ⇒ Set[SrcId] = local ⇒ seeds.flatMap(rel⇒
    if(rel.parentIsSession) rel.parentSrcId :: Nil
    else {
      val world = TxKey.of(local).world
      val index = By.srcId(classOf[BranchTask]).of(world)
      index.getOrElse(rel.parentSrcId,Nil).flatMap(_.sessionKeys(local))
    }
  ).toSet
}

case object ReportAliveBranchesKey extends WorldKey[String]("")


case class BranchTxTransform(
  branchKey: String,
  seed: Option[BranchResult],
  sessionKey: Option[SrcId],
  posts: List[MessageFromAlien],
  wasBranchResults: Values[BranchResult],
  handler: BranchHandler = VoidBranchHandler()
) extends TxTransform {
  private def saveResult: World ⇒ World = local ⇒ {
    val newChildren = handler.seeds(local)
    val newBranchResult = if(newChildren.isEmpty) Nil else List(seed.get.copy(children = newChildren))
    if(wasBranchResults == newBranchResult) local
    else add(wasBranchResults.flatMap(delete) ::: newBranchResult.flatMap(update))(local)
  }

  private def reportAliveBranches: World ⇒ World = local ⇒ sessionKey.map{ sessionKey ⇒
    val world = TxKey.of(local).world
    val index = By.srcId(classOf[BranchResult]).of(world)
    def gather(branchKey: SrcId): List[String] = {
      val children = index.getOrElse(branchKey,Nil).flatMap(_.children).map(_.hash)
      (branchKey :: children).mkString(" ") :: children.flatMap(gather)
    }
    val newReport = gather(branchKey).mkString("\n")
    if(newReport == ReportAliveBranchesKey.of(local)) local
    else ReportAliveBranchesKey.set(newReport)
      .andThen(SendToAlienKey.of(local)(sessionKey,"branches",newReport))(local)
  }.getOrElse(local)

  private def rmPosts: World ⇒ World =
    (identity[World] _ /: posts)((f,post)⇒f.andThen(post.rm))
  private def getPosts: List[String⇒String] = posts.map(m⇒(k:String)⇒m.headers.getOrElse(k,""))

  def transform(local: World): World =
    if(posts.isEmpty) handler.exchange(_⇒"")(local)
    else {
      (identity[World] _ /: (for(m ← getPosts) yield handler.exchange(m)))((a,b)⇒ a andThen b)
        .andThen(saveResult)
        .andThen(reportAliveBranches)
        .andThen(rmPosts)(local)
    }
}

class BranchOperationsImpl(registry: QAdapterRegistry) extends BranchOperations {
  private def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
  def toSeed(value: Product): BranchResult = {
    val valueAdapter = registry.byName(value.getClass.getName)
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    val id = UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes)
    BranchResult(id.toString, valueAdapter.id, byteString, Nil)
  }
  def toRel(seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel) =
    seed.hash → BranchRel(s"${seed.hash}/$parentSrcId",seed,parentSrcId,parentIsSession)
}

@assemble class BranchAssemble(registry: QAdapterRegistry, operations: BranchOperations) extends Assemble {
  def mapBranchSeedsByChild(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(BranchKey,BranchRel)] =
    for(branchResult ← branchResults; child ← branchResult.children)
      yield operations.toRel(child, branchResult.hash, parentIsSession=false)

  def joinBranchTask(
      key: SrcId,
      @by[BranchKey] seeds: Values[BranchRel]
  ): Values[(SrcId,BranchTask)] =
    List(key → BranchTaskImpl(key, seeds,
      seeds.headOption.map(_.seed).map(seed⇒registry.byId(seed.valueTypeId).decode(seed.value.toByteArray)).getOrElse(None)
    ))

  def joinTxTransform(
    key: SrcId,
    wasBranchResults: Values[BranchResult],
    @by[BranchKey] seeds: Values[BranchRel],
    @by[BranchKey] posts: Values[MessageFromAlien],
    handlers: Values[BranchHandler]
  ): Values[(SrcId,TxTransform)] =
    List(key → BranchTxTransform(key,
      seeds.headOption.map(_.seed),
      seeds.find(_.parentIsSession).map(_.parentSrcId),
      posts.sortBy(_.index),
      wasBranchResults,
      Single.option(handlers).getOrElse(VoidBranchHandler())
    ))
}

//todo relocate toAlien
//todo error in view
//todo checkUpdate()?

/*
@assemble class PurgeBranchAssemble extends Assemble {
  def purgeBranches(
    key: SrcId,
    tasks: Values[BranchTaskSender]
  ): Values[(SrcId,BranchHandler)] =
    for(task ← tasks if task.product == None) yield key → VoidBranchHandler()
}*/

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