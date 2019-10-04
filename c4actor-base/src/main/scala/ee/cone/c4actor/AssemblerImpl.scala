package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.c4

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration

@c4("RichDataCompApp") class ProtocolDataDependencies(qAdapterRegistry: QAdapterRegistry, origKeyFactory: OrigKeyFactoryFinalHolder) extends DataDependencyProvider {
  def apply(): List[DataDependencyTo[_]] =
    qAdapterRegistry.byId.values.map(_.className).toList.sorted
      .map(nm => new OriginalWorldPart(origKeyFactory.value.rawKey(nm)))
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

@c4("RichDataCompApp") class DefLongAssembleWarnPeriod extends LongAssembleWarnPeriod(Option(System.getenv("C4ASSEMBLE_WARN_PERIOD_MS")).fold(1000L)(_.toLong))

@c4("RichDataCompApp") class DefAssembleOptions extends AssembleOptions("AssembleOptions",false,0L)

@c4("RichDataCompApp") class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: DeferredSeq[DataDependencyProvider],
  composes: IndexUtil,
  byPKKeyFactory: KeyFactory,
  origKeyFactory: OrigKeyFactoryFinalHolder,
  assembleProfiler: AssembleProfiler,
  readModelUtil: ReadModelUtil,
  actorName: ActorName,
  updateProcessor: Option[UpdateProcessor],
  processors: List[UpdatesPreprocessor],
  defaultAssembleOptions: AssembleOptions,
  warnPeriod: LongAssembleWarnPeriod,
  catchNonFatal: CatchNonFatal
)(
  assembleOptionsOuterKey: AssembledKey = origKeyFactory.value.rawKey(classOf[AssembleOptions].getName),
  assembleOptionsInnerKey: String = ToPrimaryKey(defaultAssembleOptions)
) extends ToInject with LazyLogging {

  private def toTree(assembled: ReadModel, updates: Seq[N_Update], executionContext: OuterExecutionContext): ReadModel =
    readModelUtil.create((for {
      tpPair <- updates.groupBy(_.valueTypeId)
      (valueTypeId, tpUpdates) = tpPair : (Long,Seq[N_Update])
      valueAdapter <- qAdapterRegistry.byId.get(valueTypeId)
      wKey = origKeyFactory.value.rawKey(valueAdapter.className)
    } yield {
      implicit val ec: ExecutionContext = executionContext.value
      wKey -> (for {
        wasIndex <- wKey.of(assembled)
      } yield composes.mergeIndex(for {
        iPair <- tpUpdates.groupBy(_.srcId)
        (srcId, iUpdates) = iPair
        rawValue = iUpdates.last.value
        remove = composes.removingDiff(wasIndex,srcId)
        add = if(rawValue.size > 0) composes.result(srcId,valueAdapter.decode(rawValue),+1) :: Nil else Nil
        res <- remove :: add
      } yield res))
    }).toMap)

  def waitFor[T](res: Future[T], options: AssembleOptions, stage: String): T = concurrent.blocking{
    val end = NanoTimer()
    val result = Await.result(res, Duration.Inf)
    val period = end.ms
    if(period > warnPeriod.value) logger.warn(s"${options.toString} long join $period ms on $stage")
    result
  }

  // read model part:
  private def reduce(
    replace: Replace,
    wasAssembled: ReadModel, diff: ReadModel,
    options: AssembleOptions, executionContext: OuterExecutionContext
  ): ReadModel = {
    val res = replace(wasAssembled,diff,assembleProfiler.createJoiningProfiling(None),executionContext).map(_.result)(executionContext.value)
    waitFor(res, options, "read")
  }

  private def offset(events: Seq[RawEvent]): List[N_Update] = for{
    ev <- events.lastOption.toList
    lEvent <- LEvent.update(S_Offset(actorName.value,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  private def readModelAdd(replace: Replace, executionContext: OuterExecutionContext): Seq[RawEvent]=>ReadModel=>ReadModel = events => assembled => catchNonFatal {
    val options = getAssembleOptions(assembled)
    val updates = offset(events) ::: toUpdate.toUpdates(events.toList)
    val timer = NanoTimer()
    val realDiff = toTree(assembled, updates, executionContext)
    logger.trace(s"toTree ${timer.ms} ms")
    reduce(replace, assembled, realDiff, options, executionContext)
  }("reduce"){ e => // ??? exception to record
      if(events.size == 1){
        val options = getAssembleOptions(assembled)
        val updates = offset(events) ++
          events.map(ev=>S_FailedUpdates(ev.srcId, e.getMessage))
            .flatMap(LEvent.update).map(toUpdate.toUpdate)
        val failDiff = toTree(assembled, updates, executionContext)
        reduce(replace, assembled, failDiff, options, executionContext)
      } else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(readModelAdd(replace, executionContext)(a), readModelAdd(replace, executionContext)(b)))(assembled)
      }
  }
  // other parts:
  private def add(out: Seq[N_Update]): Context => Context = {
    if (out.isEmpty) identity[Context]
    else { local =>
      implicit val executionContext: ExecutionContext = local.executionContext.value
      val options = getAssembleOptions(local.assembled)
      val processedOut = composes.mayBePar(processors, options).flatMap(_.process(out)).toSeq ++ out
      val externalOut = updateProcessor.fold(processedOut)(_.process(processedOut, WriteModelKey.of(local).size))
      val diff = toTree(local.assembled, externalOut, local.executionContext)
      val profiling = assembleProfiler.createJoiningProfiling(Option(local))
      val replace = TreeAssemblerKey.of(local)
      val res = for {
        transition <- replace(local.assembled,diff,profiling,local.executionContext)
        updates <- assembleProfiler.addMeta(transition, externalOut)
      } yield {
        val nLocal = new Context(local.injected, transition.result, local.executionContext, local.transient)
        WriteModelKey.modify(_.enqueueAll(updates))(nLocal)
      }
      waitFor(res, options, "add")
      //call add here for new mortal?
    }
  }

  private def getAssembleOptions(assembled: ReadModel): AssembleOptions = {
    val index = assembleOptionsOuterKey.of(assembled).value.get.get
    composes.getValues(index,assembleOptionsInnerKey,"").collectFirst{
      case o: AssembleOptions => o
    }.getOrElse(defaultAssembleOptions)
  }

  private def getOrigIndex(context: AssembledContext, className: String): Map[SrcId,Product] = {
    val index = byPKKeyFactory.rawKey(className).of(context.assembled).value.get.get
    // val options = getAssembleOptions(context.assembled)
    UniqueIndexMap(index)(composes)
  }

  def toInject: List[Injectable] = {
    logger.debug("getDependencies started")
    val deps = getDependencies.value.flatMap(_()).toList
    logger.debug("getDependencies finished")
    TreeAssemblerKey.set(treeAssembler.replace(deps)) :::
      WriteModelDebugAddKey.set(out =>
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueueAll(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(events=>context=>
        readModelAdd(TreeAssemblerKey.of(context), context.executionContext)(events)(context.assembled)
      ) :::
      GetOrigIndexKey.set(getOrigIndex) :::
      GetAssembleOptions.set(getAssembleOptions)
  }
}

case class UniqueIndexMap[K,V](index: Index)(indexUtil: IndexUtil) extends Map[K,V] {
  def updated[B1 >: V](k: K, v: B1): Map[K, B1] = iterator.toMap.updated(k,v)
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"")).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k => (k,Single(indexUtil.getValues(index,k,""))).asInstanceOf[(K,V)] }
  def removed(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
  override def keySet: Set[K] = indexUtil.keySet(index).asInstanceOf[Set[K]] // to get keys from index
}