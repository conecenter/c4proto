package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration

case class ProtocolDataDependencies(QAdapterRegistry: QAdapterRegistry, origKeyFactory: KeyFactory) {
  def apply(): List[DataDependencyTo[_]] =
    QAdapterRegistry.byId.values.map(_.className).toList.sorted
      .map(nm ⇒ new OriginalWorldPart(origKeyFactory.rawKey(nm)))
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]],
  composes: IndexUtil,
  byPKKeyFactory: KeyFactory,
  origKeyFactory: KeyFactory,
  assembleProfiler: AssembleProfiler,
  readModelUtil: ReadModelUtil,
  actorName: String,
  updateProcessor: UpdateProcessor,
  processors: List[UpdatesPreprocessor],
  defaultAssembleOptions: AssembleOptions,
  warnPeriod: Long,
  catchNonFatal: CatchNonFatal
)(
  assembleOptionsOuterKey: AssembledKey = origKeyFactory.rawKey(classOf[AssembleOptions].getName),
  assembleOptionsInnerKey: String = ToPrimaryKey(defaultAssembleOptions)
) extends ToInject with LazyLogging {

  private def toTree(assembled: ReadModel, updates: DPIterable[N_Update])(implicit executionContext: ExecutionContext): ReadModel =
    readModelUtil.create((for {
      tpPair ← updates.groupBy(_.valueTypeId)
      (valueTypeId, tpUpdates) = tpPair
      valueAdapter ← qAdapterRegistry.byId.get(valueTypeId)
      wKey = origKeyFactory.rawKey(valueAdapter.className)
    } yield {
      wKey → (for {
        wasIndex ← wKey.of(assembled)
      } yield composes.mergeIndex(for {
        iPair ← tpUpdates.groupBy(_.srcId)
        (srcId, iUpdates) = iPair
        rawValue = iUpdates.last.value
        remove = composes.removingDiff(wasIndex,srcId)
        add = if(rawValue.size > 0) composes.result(srcId,valueAdapter.decode(rawValue),+1) :: Nil else Nil
        res ← remove :: add
      } yield res))
    }).seq.toMap)

  def waitFor[T](res: Future[T], options: AssembleOptions, stage: String): T = concurrent.blocking{
    val end = NanoTimer()
    val result = Await.result(res, Duration.Inf)
    val period = end.ms
    if(period > warnPeriod) logger.warn(s"${options.toString} long join $period ms on $stage")
    result
  }

  // read model part:
  private def reduce(
    replace: Replace,
    wasAssembled: ReadModel, diff: ReadModel,
    options: AssembleOptions, executionContext: ExecutionContext
  ): ReadModel = {
    val res = replace(wasAssembled,diff,options,assembleProfiler.createJoiningProfiling(None),executionContext).map(_.result)(executionContext)
    waitFor(res, options, "read")
  }

  private def offset(events: Seq[RawEvent]): List[N_Update] = for{
    ev ← events.lastOption.toList
    lEvent ← LEvent.update(S_Offset(actorName,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  private def readModelAdd(replace: Replace, executionContext: ExecutionContext): Seq[RawEvent]⇒ReadModel⇒ReadModel = events ⇒ assembled ⇒ catchNonFatal {
    val options = getAssembleOptions(assembled)
    val updates = offset(events) ::: toUpdate.toUpdates(events.toList)
    val realDiff = toTree(assembled, composes.mayBePar(updates, options))(executionContext)
    reduce(replace, assembled, realDiff, options, executionContext)
  }("reduce"){ e ⇒ // ??? exception to record
      if(events.size == 1){
        val options = getAssembleOptions(assembled)
        val updates = offset(events) ++
          events.map(ev⇒S_FailedUpdates(ev.srcId, e.getMessage))
            .flatMap(LEvent.update).map(toUpdate.toUpdate)
        val failDiff = toTree(assembled, updates)(executionContext)
        reduce(replace, assembled, failDiff, options, executionContext)
      } else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(readModelAdd(replace, executionContext)(a), readModelAdd(replace, executionContext)(b)))(assembled)
      }
  }
  // other parts:
  private def add(out: Seq[N_Update]): Context ⇒ Context = {
    if (out.isEmpty) identity[Context]
    else { local ⇒
      implicit val executionContext: ExecutionContext = local.executionContext.value
      val options = getAssembleOptions(local.assembled)
      val processedOut = composes.mayBePar(processors, options).flatMap(_.process(out)).to[Seq] ++ out
      val externalOut = updateProcessor.process(processedOut, WriteModelKey.of(local).size)
      val diff = toTree(local.assembled, externalOut)(executionContext)
      val profiling = assembleProfiler.createJoiningProfiling(Option(local))
      val replace = TreeAssemblerKey.of(local)
      val res = for {
        transition ← replace(local.assembled,diff,options,profiling, executionContext)
        updates ← assembleProfiler.addMeta(transition, externalOut)
      } yield {
        val nLocal = new Context(local.injected, transition.result, local.executionContext, local.transient)
        WriteModelKey.modify(_.enqueue(updates))(nLocal)
      }
      waitFor(res, options, "add")
      //call add here for new mortal?
    }
  }

  private def getAssembleOptions(assembled: ReadModel): AssembleOptions = {
    val index = assembleOptionsOuterKey.of(assembled).value.get.get
    composes.getValues(index,assembleOptionsInnerKey,"",defaultAssembleOptions).collectFirst{
      case o: AssembleOptions ⇒ o
    }.getOrElse(defaultAssembleOptions)
  }

  private def getOrigIndex(context: AssembledContext, className: String): Map[SrcId,Product] = {
    val index = byPKKeyFactory.rawKey(className).of(context.assembled).value.get.get
    val options = getAssembleOptions(context.assembled)
    UniqueIndexMap(index,options)(composes)
  }

  def toInject: List[Injectable] = {
    logger.debug("getDependencies started")
    val deps = getDependencies()
    logger.debug("getDependencies finished")
    TreeAssemblerKey.set(treeAssembler.replace(deps)) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(events⇒context⇒
        readModelAdd(TreeAssemblerKey.of(context), context.executionContext.value)(events)(context.assembled)
      ) :::
      GetOrigIndexKey.set(getOrigIndex) :::
      GetAssembleOptions.set(getAssembleOptions)
  }
}

case class UniqueIndexMap[K,V](index: Index, options: AssembleOptions)(indexUtil: IndexUtil) extends Map[K,V] {
  def +[B1 >: V](kv: (K, B1)): Map[K, B1] = iterator.toMap + kv
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"",options)).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k ⇒ (k,Single(indexUtil.getValues(index,k,"",options))).asInstanceOf[(K,V)] }
  def -(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
  override def keySet: Set[K] = indexUtil.keySet(index).asInstanceOf[Set[K]] // to get keys from index
}