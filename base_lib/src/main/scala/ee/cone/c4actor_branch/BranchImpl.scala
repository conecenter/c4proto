
package ee.cone.c4actor_branch

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchProtocol._
import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.Function.chain
import scala.collection.immutable.Seq

case object SessionKeyToConnectionKey extends TransientLens[Map[String,String]](Map.empty)

@c4multi("BranchApp") final case class BranchTaskImpl(branchKey: String, seeds: List[BranchRel], product: Product)(
  getBranchTask: GetByPK[BranchTask],
  toAlienSender: ToAlienSender,
) extends BranchTask with LazyLogging {
  def sending: Context => (Send,Send) = local => {
    val wasSessionKeyToConnectionKey = SessionKeyToConnectionKey.of(local);
    val newSessionKeyToConnectionKey: Map[String,String] = {
      val newSessionKeys: Set[String] = sessionKeys()(local)
      newSessionKeys.flatMap(sessionKey=>toAlienSender.getConnectionKey(sessionKey,local).map(sessionKey->_)).toMap
    }
    val(keepTo,freshTo) = newSessionKeyToConnectionKey.keySet.partition(sessionKey =>
      newSessionKeyToConnectionKey.get(sessionKey) == wasSessionKeyToConnectionKey.get(sessionKey)
    )
    def sendingPart(to: Set[String]): Send =
      if(to.isEmpty) None
      else Some((eventName,data) =>
        toAlienSender.send(to.toList, eventName, data)
          .andThen(SessionKeyToConnectionKey.set(newSessionKeyToConnectionKey))
      )
    (sendingPart(keepTo), sendingPart(freshTo))
  }

  def sessionKeys(visited: Set[SrcId]): Context => Set[String] = local =>
    if (!visited(branchKey))
      seeds.flatMap(rel =>
        if (rel.parentIsSession) rel.parentSrcId :: Nil
        else {
          val index = getBranchTask.ofA(local)
          val newVisited = visited + branchKey
          index.get(rel.parentSrcId).toList.flatMap(_.sessionKeys(newVisited)(local))
        }
      ).toSet
    else Set.empty

  def relocate(to: String): Context => Context = local => {
    val(toSessions, toNested) = seeds.partition(_.parentIsSession)
    val sessionKeys = toSessions.map(_.parentSrcId)
    val send = toAlienSender.send(sessionKeys,"relocateHash",to)
    val nest = toNested.map((rel:BranchRel) => relocateSeed(rel.parentSrcId,rel.seed.position,to))
    send.andThen(chain(nest))(local)
  }

  def relocateSeed(branchKey: String, position: String, to: String): Context => Context = {
    logger.debug(s"relocateSeed: [$branchKey] [$position] [$to]")
    identity
  } //todo emulate post to branch?
}

case object ReportAliveBranchesKey extends TransientLens[String]("")

case object EmptyBranchMessage extends BranchMessage {
  def method: String = ""
  def header: String => String = _=>""
  def body: ByteString = ByteString.EMPTY
  def deletes: Seq[LEvent[Product]] = Nil
}

@c4("BranchApp") final class EnableBranchScaling
  extends EnableSimpleScaling(classOf[BranchTxTransform])

@c4multi("BranchApp") final case class BranchTxTransform(
  branchKey: String,
  seed: Option[S_BranchResult],
  sessionKeys: List[SrcId],
  requests: List[BranchMessage],
  handler: BranchHandler
)(
  getS_BranchResult: GetByPK[S_BranchResult],
  txAdd: LTxAdd,
  toAlienSender: ToAlienSender,
  branchErrorSaver: Option[BranchErrorSaver]
) extends TxTransform with LazyLogging {
  private def saveResult: Context => Context = local => {
    //if(seed.isEmpty && newChildren.nonEmpty) println(s"newChildren: ${handler}")
    //println(s"S_BranchResult $wasBranchResults == $newBranchResult == ${wasBranchResults == newBranchResult}")
    val wasBranchResults = getS_BranchResult.ofA(local).get(branchKey).toList
    //
    val wasChildren = wasBranchResults.flatMap(_.children)
    val newChildren = handler.seeds(local)
    if(wasChildren == newChildren) local
    else {
      val newBranchResult = if(newChildren.isEmpty) Nil else List(seed.get.copy(children = newChildren))
      txAdd.add(wasBranchResults.flatMap(LEvent.delete) ++ newBranchResult.flatMap(LEvent.update))(local)
    }
    /* proposed:
    val newBranchResults = seed.toList.map(_.copy(children = handler.seeds(local)))
    if(wasBranchResults == newBranchResults) local
    else add(
      wasBranchResults.flatMap(delete) ++ newBranchResults.flatMap(update)
    )(local)*/
  }

  private def reportAliveBranches(local: Context): Context = {
    val wasReport = ReportAliveBranchesKey.of(local)
    if(sessionKeys.isEmpty){
      if(wasReport.isEmpty) local else ReportAliveBranchesKey.set("")(local)
    }
    else {
      val index = getS_BranchResult.ofA(local)
      def gather(branchKey: SrcId, seen: Set[SrcId]): List[String] =
        if (!seen(branchKey)) {
          val children = index.get(branchKey).toList.flatMap(_.children).map(_.hash)
          val newSeen = seen + branchKey
          (branchKey :: children).mkString(",") :: children.flatMap(gather(_, newSeen))
        } else Nil
      val newReport = gather(branchKey, Set.empty).mkString(";")
      if(newReport == wasReport) local
      else ReportAliveBranchesKey.set(newReport)
        .andThen(sendToAll("branches",newReport))(local)

    }
  }

  private def getPosts: List[BranchMessage] = {
    val posts = requests.filter(_.method=="POST")
    if(posts.isEmpty) List(EmptyBranchMessage) else posts
  }

  private def sendToAll(evType: String, data: String): Context => Context =
    toAlienSender.send(sessionKeys,evType,data)

  private def reportError: Context => Context =
    sendToAll("fail",s"$branchKey\nInternal Page Error")

  private def incrementErrors: Context => Context =
    ErrorKey.modify(new Exception :: _)

  private def saveErrors(local: Context): Context =
    branchErrorSaver.fold(local)(_.saveErrors(local, branchKey, sessionKeys, ErrorKey.of(local)))

  private def rmRequestsErrors(local: Context): Context = {
    chain(requests.map{ request =>
      val sessionKey = request.header("x-r-session")
      val index = request.header("x-r-index")
      val deletes = request.deletes
      if(sessionKey.isEmpty) txAdd.add(deletes)
      else toAlienSender.send(List(sessionKey), "ackChange", s"$branchKey $index").andThen(txAdd.add(deletes))
    }).andThen(ErrorKey.set(Nil))(local)
  }

  private def doExchange(message: BranchMessage, local: Context): Context =
    handler.exchange(message)(local)

  private def doNormalTransform(local: Context): Context =
    chain(getPosts.map(msg=>doExchange(msg,_)))
    .andThen(saveResult).andThen(reportAliveBranches)(local)

  def transform(local: Context): Context = {
    val end = NanoTimer()
    if(requests.nonEmpty)
      logger.debug(s"branch $branchKey tx begin ${requests.map(r=>r.header("x-r-alien-date")).mkString("(",", ",")")}")
    val errors = ErrorKey.of(local)
    val res = if(errors.nonEmpty && requests.nonEmpty)
      rmRequestsErrors(saveErrors(local))
    else if(errors.size == 1)
      reportError.andThen(incrementErrors)(local)
    else rmRequestsErrors(doNormalTransform(local))
    if(requests.nonEmpty)
      logger.debug(s"branch $branchKey tx done in ${end.ms} ms")
    res
  }
}

//class UnconfirmedException() extends Exception
@c4("BranchApp") final class BranchOperationsImpl(registry: QAdapterRegistry, idGenUtil: IdGenUtil) extends BranchOperations {
  def toSeed(value: Product): S_BranchResult = {
    val valueAdapter = registry.byName(value.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(value))
    val id = idGenUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    S_BranchResult(id, valueAdapter.id, bytes, Nil, "")
  }
  def toRel(seed: S_BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel) =
    seed.hash -> BranchRel(s"${seed.hash}/$parentSrcId",seed,parentSrcId,parentIsSession)
}

@c4assemble("BranchApp") class BranchAssembleBase(
  registry: QAdapterRegistry, operations: BranchOperations,
  branchTaskImplFactory: BranchTaskImplFactory,
  branchTxTransformFactory: BranchTxTransformFactory
) extends LazyLogging {
  def mapBranchSeedsByChild(
    key: SrcId,
    branchResult: Each[S_BranchResult]
  ): Values[(BranchKey,BranchRel)] = for {
    child <- branchResult.children
  } yield operations.toRel(child, branchResult.hash, parentIsSession=false)

  def joinBranchTask(
      key: SrcId,
      wasBranchResults: Values[S_BranchResult],
      @by[BranchKey] seeds: Values[BranchRel]
  ): Values[(SrcId,BranchTask)] = {
    val seed = seeds.headOption.map(_.seed).getOrElse(Single(wasBranchResults))
    registry.byId.get(seed.valueTypeId).map(_.decode(seed.value.toByteArray))
      .map(product => key -> branchTaskImplFactory.create(key, seeds.toList, product)).toList
    // may result in some garbage branches in the world?

    //println(s"join_task $key ${wasBranchResults.size} ${seeds.size}")
  }

  def joinTxTransform(
    key: SrcId,
    @by[BranchKey] seeds: Values[BranchRel],
    @by[BranchKey] requests: Values[BranchMessage],
    handler: Each[BranchHandler]
  ): Values[(SrcId,TxTransform)] =
    List(key -> branchTxTransformFactory.create(key,
        seeds.headOption.map(_.seed),
        seeds.filter(_.parentIsSession).map(_.parentSrcId).toList,
        requests.sortBy(req=>(req.header("x-r-index") match{
          case "" => 0L
          case s => s.toLong
        },ToPrimaryKey(req))).toList,
        handler
    ))

  def redrawByBranch(
    key: SrcId,
    redraw: Each[U_Redraw]
  ): Values[(BranchKey, BranchMessage)] =
    List(redraw.branchKey -> RedrawBranchMessage(redraw))
}

case class RedrawBranchMessage(redraw: U_Redraw) extends BranchMessage {
  def method: String = "POST"
  def header: String => String = { case "x-r-redraw" => "1" case _ => "" }
  def body: okio.ByteString = okio.ByteString.EMPTY
  def deletes: Seq[LEvent[Product]] = LEvent.delete(redraw)
}

//todo relocate toAlien
//todo error in view
//todo checkUpdate()?

/*
@assemble class PurgeBranchAssemble   {
  def purgeBranches(
    key: SrcId,
    tasks: Values[BranchTaskSender]
  ): Values[(SrcId,BranchHandler)] =
    for(task <- tasks if task.product == None) yield key -> VoidBranchHandler()
}*/

/*
case class ProductProtoAdapter(className: SrcId, id: Long)(val adapter: ProtoAdapter[Product])
def createWorld: World => World = setupAssembler andThen setupAdapters
private def setupAdapters =
    By.srcId(classOf[ProductProtoAdapter]).set(protocols.flatMap(_.adapters).map(adapter=>
      adapter.className ->
        ProductProtoAdapter(adapter.id,adapter.className)(adapter.asInstanceOf[ProtoAdapter[Product]]) :: Nil
    ).toMap)


@assemble class AdapterAssemble   {
  type AnnotationId = Long
  def joinProductProtoAdapterByAnnotationId(
    key: SrcId,
    adapters: Values[ProductProtoAdapter]
  ): Values[(AnnotationId,ProductProtoAdapter)] =
    adapters.map(a=>a.id->a)
}

*/

// /connection x-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])

/* embed plan:
S_TcpWrite to many conns
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
"x-r-vdom-branch"

?errors in embed
?bind/positioning: ref=['embed','parent',key]
*/