
package ee.cone.c4actor

import java.nio.ByteBuffer
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4actor.BranchProtocol.{BranchResult, Redraw, SessionFailure}
import ee.cone.c4actor.BranchTypes._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.ToByteString
import okio.ByteString

import Function.chain
import scala.collection.immutable.Seq

case object SessionKeysKey extends TransientLens[Set[BranchRel]](Set.empty)

case class BranchTaskImpl(branchKey: String, seeds: List[BranchRel], product: Product) extends BranchTask with LazyLogging {
  def sending: Context ⇒ (Send,Send) = local ⇒ {
    val newSessionKeys = sessionKeys(local)
    val(keepTo,freshTo) = newSessionKeys.partition(SessionKeysKey.of(local))
    val send = SendToAlienKey.of(local)
    def sendingPart(to: Set[BranchRel]): Send =
      if(to.isEmpty) None
      else Some((eventName,data) ⇒
        send(to.map(_.parentSrcId).toList, eventName, data)
          .andThen(SessionKeysKey.set(newSessionKeys))
      )
    (sendingPart(keepTo), sendingPart(freshTo))
  }

  def sessionKeys: Context ⇒ Set[BranchRel] = local ⇒ seeds.flatMap(rel⇒
    if(rel.parentIsSession) rel :: Nil
    else {
      val index = ByPK(classOf[BranchTask]).of(local)
      index.get(rel.parentSrcId).toList.flatMap(_.sessionKeys(local))
    }
  ).toSet
  def relocate(to: String): Context ⇒ Context = local ⇒ {
    val(toSessions, toNested) = seeds.partition(_.parentIsSession)
    val sessionKeys = toSessions.map(_.parentSrcId)
    val send = SendToAlienKey.of(local)(sessionKeys,"relocateHash",to)
    val nest = toNested.map((rel:BranchRel) ⇒ relocateSeed(rel.parentSrcId,rel.seed.position,to))
    send.andThen(chain(nest))(local)
  }

  def relocateSeed(branchKey: String, position: String, to: String): Context ⇒ Context = {
    logger.debug(s"relocateSeed: [$branchKey] [$position] [$to]")
    identity
  } //todo emulate post to branch?
}

case object ReportAliveBranchesKey extends TransientLens[String]("")

case object EmptyBranchMessage extends BranchMessage {
  def header: String ⇒ String = _⇒""
  def body: ByteString = ByteString.EMPTY
  def deletes: Seq[LEvent[Product]] = Nil
}

case class BranchTxTransform(
  branchKey: String,
  seed: Option[BranchResult],
  sessionKeys: List[SrcId],
  posts: List[BranchMessage],
  handler: BranchHandler
) extends TxTransform with LazyLogging {
  private def saveResult: Context ⇒ Context = local ⇒ {
    //if(seed.isEmpty && newChildren.nonEmpty) println(s"newChildren: ${handler}")
    //println(s"BranchResult $wasBranchResults == $newBranchResult == ${wasBranchResults == newBranchResult}")
    val wasBranchResults = ByPK(classOf[BranchResult]).of(local).get(branchKey).toList
    //
    val wasChildren = wasBranchResults.flatMap(_.children)
    val newChildren = handler.seeds(local)
    if(wasChildren == newChildren) local
    else {
      val newBranchResult = if(newChildren.isEmpty) Nil else List(seed.get.copy(children = newChildren))
      TxAdd(wasBranchResults.flatMap(LEvent.delete) ++ newBranchResult.flatMap(LEvent.update))(local)
    }
    /* proposed:
    val newBranchResults = seed.toList.map(_.copy(children = handler.seeds(local)))
    if(wasBranchResults == newBranchResults) local
    else add(
      wasBranchResults.flatMap(delete) ++ newBranchResults.flatMap(update)
    )(local)*/
  }

  private def reportAliveBranches: Context ⇒ Context = local ⇒ {
    val wasReport = ReportAliveBranchesKey.of(local)
    if(sessionKeys.isEmpty){
      if(wasReport.isEmpty) local else ReportAliveBranchesKey.set("")(local)
    }
    else {
      val index = ByPK(classOf[BranchResult]).of(local)
      def gather(branchKey: SrcId): List[String] = {
        val children = index.get(branchKey).toList.flatMap(_.children).map(_.hash)
        (branchKey :: children).mkString(",") :: children.flatMap(gather)
      }
      val newReport = gather(branchKey).mkString(";")
      if(newReport == wasReport) local
      else ReportAliveBranchesKey.set(newReport)
        .andThen(sendToAll("branches",newReport))(local)

    }
  }

  private def getPosts: List[BranchMessage] =
    if(posts.isEmpty) List(EmptyBranchMessage) else posts

  private def sendToAll(evType: String, data: String): Context ⇒ Context =
    local ⇒ SendToAlienKey.of(local)(sessionKeys,evType,data)(local)

  private def errorText: Context ⇒ String = local ⇒ ErrorKey.of(local).map{
    case e:BranchError ⇒ e.message
    case _ ⇒ ""
  }.mkString("\n")

  private def reportError: String ⇒ Context ⇒ Context = text ⇒
    sendToAll("fail",s"$branchKey\n$text")

  private def incrementErrors: Context ⇒ Context =
    ErrorKey.modify(new Exception :: _)

  private def savePostsErrors: String ⇒ Context ⇒ Context = text ⇒ {
    val now = System.currentTimeMillis
    val failure = SessionFailure(UUID.randomUUID.toString,text,now,sessionKeys)
    TxAdd(LEvent.update(failure))
  }

  private def rmPostsErrors: Context ⇒ Context = local ⇒ {
    val send = SendToAlienKey.of(local)
    chain(posts.map{ post ⇒
      val sessionKey = post.header("X-r-session")
      val index = post.header("X-r-index")
      val deletes = post.deletes
      if(sessionKey.isEmpty) TxAdd(deletes)
      else send(List(sessionKey), "ackChange", s"$branchKey $index").andThen(TxAdd(deletes))
    }).andThen(ErrorKey.set(Nil))(local)
  }

  def transform(local: Context): Context = {
    if(posts.nonEmpty)
      logger.debug(s"branch $branchKey tx begin ${posts.map(r⇒r.header("X-r-alien-date")).mkString("(",", ",")")}")
    val errors = ErrorKey.of(local)
    var res = if(errors.nonEmpty && posts.nonEmpty)
      savePostsErrors(errorText(local)).andThen(rmPostsErrors)(local)
    else if(errors.size == 1)
      reportError(errorText(local)).andThen(incrementErrors)(local)
    else chain(getPosts.map(handler.exchange))
      .andThen(saveResult).andThen(reportAliveBranches)
      .andThen(rmPostsErrors)(local)
    res
  }
}

//class UnconfirmedException() extends Exception

class BranchOperationsImpl(registry: QAdapterRegistry, idGenUtil: IdGenUtil) extends BranchOperations {
  def toSeed(value: Product): BranchResult = {
    val valueAdapter = registry.byName(value.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(value))
    val id = idGenUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    BranchResult(id, valueAdapter.id, bytes, Nil, "")
  }
  def toRel(seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel) =
    seed.hash → BranchRel(s"${seed.hash}/$parentSrcId",seed,parentSrcId,parentIsSession)
}

@assemble class BranchAssemble(registry: QAdapterRegistry, operations: BranchOperations)   {
  def mapBranchSeedsByChild(
    key: SrcId,
    branchResult: Each[BranchResult]
  ): Values[(BranchKey,BranchRel)] = for {
    child ← branchResult.children
  } yield operations.toRel(child, branchResult.hash, parentIsSession=false)

  def joinBranchTask(
      key: SrcId,
      wasBranchResults: Values[BranchResult],
      @by[BranchKey] seeds: Values[BranchRel]
  ): Values[(SrcId,BranchTask)] = {
    val seed = seeds.headOption.map(_.seed).getOrElse(Single(wasBranchResults))
    registry.byId.get(seed.valueTypeId).map(_.decode(seed.value.toByteArray))
      .map(product => key → BranchTaskImpl(key, seeds.toList, product)).toList
    // may result in some garbage branches in the world?

    //println(s"join_task $key ${wasBranchResults.size} ${seeds.size}")
  }

  def joinTxTransform(
    key: SrcId,
    @by[BranchKey] seeds: Values[BranchRel],
    @by[BranchKey] posts: Values[BranchMessage],
    handler: Each[BranchHandler]
  ): Values[(SrcId,TxTransform)] =
    List(key → BranchTxTransform(key,
        seeds.headOption.map(_.seed),
        seeds.filter(_.parentIsSession).map(_.parentSrcId).toList,
        posts.sortBy(post⇒(post.header("X-r-index") match{
          case "" ⇒ 0L
          case s ⇒ s.toLong
        },ToPrimaryKey(post))).toList,
        handler
    ))

  type SessionKey = SrcId

  def failuresBySession(
    key: SrcId,
    failure: Each[SessionFailure]
  ): Values[(SessionKey,SessionFailure)] =
    for(k ← failure.sessionKeys) yield k → failure

  def joinSessionFailures(
    key: SrcId,
    @by[SessionKey] failures: Values[SessionFailure]
  ): Values[(SrcId,SessionFailures)] =
    List(WithPK(SessionFailures(key,failures.sortBy(_.time).toList)))

  def redrawByBranch(
    key: SrcId,
    redraw: Each[Redraw]
  ): Values[(BranchKey, BranchMessage)] =
    List(redraw.branchKey → RedrawBranchMessage(redraw))
}

case class RedrawBranchMessage(redraw: Redraw) extends BranchMessage {
  def header: String ⇒ String = { case "X-r-redraw" ⇒ "1" case _ ⇒ "" }
  def body: okio.ByteString = okio.ByteString.EMPTY
  def deletes: Seq[LEvent[Product]] = LEvent.delete(redraw)
}

case class SessionFailures(sessionKey: SrcId, failures: List[SessionFailure])

//todo relocate toAlien
//todo error in view
//todo checkUpdate()?

/*
@assemble class PurgeBranchAssemble   {
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


@assemble class AdapterAssemble   {
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