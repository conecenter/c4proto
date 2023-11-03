package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4di.Types.ComponentFactory
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4proto.{HasId, ToByteString}
import okio.ByteString

import scala.collection.immutable
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration

@c4("RichDataCompApp") final class ProtocolDataDependencies(
  qAdapterRegistry: QAdapterRegistry,
  origKeyFactory: OrigKeyFactoryFinalHolder,
  composes: IndexUtil,
  origPartitionerRegistry: OrigPartitionerRegistry,
) extends DataDependencyProvider {
  def getRules: List[WorldPartRule] =
    qAdapterRegistry.byId.values.toList.sortBy(_.className).map{ valueAdapter =>
      val key = origKeyFactory.value.rawKey(valueAdapter.className)
      val partitionedKeys = for {
        partitioner <- origPartitionerRegistry.getByAdapter(valueAdapter)
        partition <- partitioner.partitions
      } yield composes.addNS(key,partition)
      new OriginalWorldPart(key :: partitionedKeys)
    }
}

//case object TreeAssemblerKey extends SharedComponentKey[Replace]

@c4("RichDataCompApp") final class DefLongAssembleWarnPeriod extends LongAssembleWarnPeriod(Option(System.getenv("C4ASSEMBLE_WARN_PERIOD_MS")).fold(1000L)(_.toLong))

@c4("RichDataCompApp") final class DefAssembleOptions extends AssembleOptions("AssembleOptions",false,0L)

@c4("RichDataCompApp") final class OrigPartitionerRegistry(
  origPartitionerList: List[GeneralOrigPartitioner],
)(
  byClassName:  Map[String, GeneralOrigPartitioner] =
    CheckedMap(origPartitionerList.map{ case c: OrigPartitioner[_] => c.cl.getName -> c })
){
  def getByAdapter(valueAdapter: HasId): List[OrigPartitioner[Product]] =
    byClassName.get(valueAdapter.protoOrigMeta.cl.getName).toList
      .asInstanceOf[List[OrigPartitioner[Product]]]
}

object SpreadUpdates extends SpreadHandler[N_Update] {
  val power = 5
  val partCount = 1 << power
  val empty = Array.empty[N_Update]
  def toPos(it: N_Update): Int = it.srcId.hashCode & (partCount-1)
  def createPart(sz: Int): Array[N_Update] = if (sz > 0) new Array(sz) else empty
  def createRoot(sz: Int): Array[Array[N_Update]] = new Array(sz)
}

@c4("RichDataCompApp") final class AssemblerUtil(
  qAdapterRegistry: QAdapterRegistry,
  composes: IndexUtil,
  origKeyFactory: OrigKeyFactoryFinalHolder,
  spreader: Spreader,
  warnPeriod: LongAssembleWarnPeriod,
  replace: Replace,
  activeOrigKeyRegistry: ActiveOrigKeyRegistry,
  origPartitionerRegistry: OrigPartitionerRegistry,
) extends LazyLogging {
  def seq[T](s: Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[T]] = Future.sequence(s)

  def toTreeReplace(assembled: ReadModel, updates: Seq[N_Update], profiling: JoiningProfiling, executionContext: OuterExecutionContext): ReadModel = {
    val end = NanoTimer()
    val txName = Thread.currentThread.getName
    val isActiveOrig: Set[AssembledKey] = activeOrigKeyRegistry.values
    val outFactory = composes.createOutFactory(0, +1)
    val ec: ExecutionContext = executionContext.value
    logger.debug("toTreeReplace indexGroups before")
    def handle(updatesPart: Array[N_Update]): Seq[(AssembledKey, Array[Array[DOut]])] = for {
      tpPair <- updatesPart.groupBy(_.valueTypeId).toSeq
      (valueTypeId, tpUpdates) = tpPair: (Long, Array[N_Update])
      valueAdapter <- qAdapterRegistry.byId.get(valueTypeId).toSeq
      wKey <- Seq(origKeyFactory.value.rawKey(valueAdapter.className)) if isActiveOrig(wKey)
      updatesBySrcId = tpUpdates.groupBy(_.srcId)
      adds: Iterable[DOut] = for {
        iPair <- updatesBySrcId
        (srcId, iUpdates) = iPair
        rawValue = iUpdates.last.value if rawValue.size > 0
      } yield outFactory.result(srcId, valueAdapter.decode(rawValue))
      partitionerList = origPartitionerRegistry.getByAdapter(valueAdapter)
      wasIndex = wKey.of(assembled)
      removes = composes.removingDiff(0, wasIndex, updatesBySrcId.keys)
      changes = adds ++ removes
      partitionedIndexFList = for {
        partitioner <- partitionerList
        (nsName, nsChanges) <- changes.groupBy(change => partitioner.handle(composes.getValue(change)))
      } yield composes.addNS(wKey, nsName) -> Array(nsChanges.toArray)
      kv <- (if(changes.isEmpty) Nil else (wKey -> Array(changes.toArray)) :: partitionedIndexFList)
    } yield kv
    val updatesArr = updates.toArray
    val tasks = spreader.spread(updatesArr, updatesArr.length, SpreadUpdates.partCount, SpreadUpdates)
      .filter(_.length>0).sortBy(-_.length)
    val taskResultsF = seq(tasks.map{ part => Future{ handle(part) }(ec) })(parasitic)
    val taskResults = Await.result(taskResultsF, Duration.Inf)
    val diff = taskResults.flatten.groupMap(_._1)(_._2).transform((_,v)=>v.toArray.flatten).toSeq
    //assert(diff.map(_._1).distinct.size == diff.size)
    logger.debug("toTreeReplace indexGroups after")
    val willAssembled = replace.replace(assembled,diff,profiling,executionContext)
    val period = end.ms
//    if(logger.underlying.isDebugEnabled){
//      val ids = updates.map(_.valueTypeId).distinct.map(v=>s"0x${java.lang.Long.toHexString(v)}").mkString(" ")
//      logger.debug(s"checked: ${transition.taskLog.size} rules by $txName ($ids)")
//    }
    if (period > warnPeriod.value) logger.warn(s"long join $period ms by $txName")
    (new AssemblerProfiling).debugPeriod(period)
    willAssembled
  }
}

class AssemblerProfiling extends LazyLogging {
  def id = s"T-${Thread.currentThread.getId}"
  def debugPeriod(period: Long): Unit = logger.debug(s"$id was joining for $period ms")
  def debugOffsets(stage: String, offsets: Seq[NextOffset]): Unit =
    logger.debug(s"$id $stage "+offsets.map(s => s"E-$s").distinct.mkString(","))
}
/*
    val reg = CheckedMap(
      replace.active.collect{
        case t: DataDependencyTo[_] => t.outputWorldKey
      }.collect{
        case k: JoinKey if keyFactory.rawKey(k.valueClassName)==k =>
          k.valueClassName -> k
      }
    )*/
class ActiveOrigKeyRegistry(val values: Set[AssembledKey])



@c4("RichDataCompApp") final class GetAssembleOptionsImpl(
  composes: IndexUtil,
  origKeyFactory: OrigKeyFactoryFinalHolder,
  defaultAssembleOptions: AssembleOptions,
)(
  assembleOptionsOuterKey: AssembledKey = origKeyFactory.value.rawKey(classOf[AssembleOptions].getName),
  assembleOptionsInnerKey: String = ToPrimaryKey(defaultAssembleOptions)
) extends GetAssembleOptions {
  def get(assembled: ReadModel): AssembleOptions = {
    val index = assembleOptionsOuterKey.of(assembled)
    composes.getValues(index,assembleOptionsInnerKey,"").collectFirst{
      case o: AssembleOptions => o
    }.getOrElse(defaultAssembleOptions)
  }
}

@c4("RichDataCompApp") final class ReadModelAddImpl(
  utilOpt: DeferredSeq[AssemblerUtil],
  toUpdate: ToUpdate,
  assembleProfiler: AssembleProfiler,
  actorName: ActorName,
  catchNonFatal: CatchNonFatal,
) extends ReadModelAdd with LazyLogging {
  // read model part:
  private def reduce(
    wasAssembled: ReadModel, updates: Seq[N_Update],
    executionContext: OuterExecutionContext
  ): ReadModel = {
    val profiling = assembleProfiler.createJoiningProfiling(None)
    val util = Single(utilOpt.value)
    util.toTreeReplace(wasAssembled, updates, profiling, executionContext)
  }
  private def offset(events: Seq[RawEvent]): List[N_Update] = for{
    ev <- events.lastOption.toList
    lEvent <- LEvent.update(S_Offset(actorName.value,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  def add(executionContext: OuterExecutionContext, events: Seq[RawEvent]): ReadModel=>ReadModel = assembled => catchNonFatal {
    logger.debug("starting toUpdate")
    val updates: List[N_Update] = offset(events) ::: toUpdate.toUpdates(events.toList,"rma").map(toUpdate.toUpdateLost)
    logger.debug("done toUpdate")
    (new AssemblerProfiling).debugOffsets("starts-reducing", events.map(_.srcId))
    reduce(assembled, updates, executionContext)
  }("reduce"){ e => // ??? exception to record
    if(events.size == 1){
      val updates = offset(events) ++
        events.map(ev=>S_FailedUpdates(ev.srcId, e.getMessage))
          .flatMap(LEvent.update).map(toUpdate.toUpdate)
      reduce(assembled, updates, executionContext)
    } else {
      val(a,b) = events.splitAt(events.size / 2)
      Function.chain(Seq(add(executionContext,a), add(executionContext,b)))(assembled)
    }
  }
}

@c4("RichDataCompApp") final class UpdateFromUtilImpl(
  qAdapterRegistry: QAdapterRegistry,
  origKeyFactory: OrigKeyFactoryFinalHolder,
  indexUtil: IndexUtil,
  updateMapUtil: UpdateMapUtil,
) extends UpdateFromUtil {
  def get(local: Context, updates: Seq[N_Update]): Seq[N_UpdateFrom] =
    updateMapUtil.toUpdatesFrom(updates.toList, u => {
      val valueAdapter = qAdapterRegistry.byId(u.valueTypeId)
      val wKey = origKeyFactory.value.rawKey(valueAdapter.className)
      val index = wKey.of(local.assembled)
      Single.option(indexUtil.getValues(index,u.srcId,""))
        .fold(ByteString.EMPTY)(item=>ToByteString(valueAdapter.encode(item)))
    })
}

@c4("RichDataCompApp") final class RawTxAddImpl(
  utilOpt: DeferredSeq[AssemblerUtil],
  updateFromUtil: UpdateFromUtil,
  assembleProfiler: AssembleProfiler,
  updateProcessor: Option[UpdateProcessor],
  processors: List[UpdatesPreprocessor],
  getAssembleOptions: GetAssembleOptions,
) extends RawTxAdd with Executable with Early with LazyLogging {
  def run(): Unit = {
    logger.info("assemble-preload start")
    ignorePreloadedUtil(utilOpt.value)
    logger.info("assemble-preload end")
  } // we just want to load assemble-components in parallel with RootConsumer start
  private def ignorePreloadedUtil[T](value: Seq[AssemblerUtil]): Unit = ()
  // other parts:
  def add(out: Seq[N_Update]): Context => Context =
    if (out.isEmpty) identity[Context]
    else doAdd(out,_)
  private def doAdd(out: Seq[N_Update], local: Context): Context = {
    val processedOut: List[N_Update] = processors.flatMap(_.process(out)) ++ out
    val externalOut = updateProcessor.fold(processedOut)(_.process(processedOut, WriteModelKey.of(local).size).toList)
    val profiling = assembleProfiler.createJoiningProfiling(Option(local))
    val util = Single(utilOpt.value)
    val result = util.toTreeReplace(local.assembled, externalOut, profiling, local.executionContext)
    val updates = Await.result(assembleProfiler.addMeta(new WorldTransition(profiling, Future.successful(Nil)), externalOut), Duration.Inf)
    val nLocal = new Context(local.injected, result, local.executionContext, local.transient)
    WriteModelKey.modify(_.enqueueAll(updateFromUtil.get(local,updates)))(nLocal)
    //call add here for new mortal?
  }
}

@c4("RichDataCompApp") final case class TxAddImpl()(
  toUpdate: ToUpdate,
  rawTxAdd: RawTxAdd,
) extends LTxAdd with LazyLogging {
  def add[M<:Product](out: Seq[LEvent[M]]): Context=>Context =
    local => if(out.isEmpty) local else {
      logger.debug(out.map(v=>s"\norig added: $v").mkString)
      rawTxAdd.add(out.map(toUpdate.toUpdate))(local)
    }
}

@c4("RichDataCompApp") final class AssemblerInit(
  treeAssembler: TreeAssembler,
  getDependencies: DeferredSeq[DataDependencyProvider],
  isTargetWorldPartRules: List[IsTargetWorldPartRule],
) extends LazyLogging {
  private lazy val rules = {
    logger.debug("getDependencies started")
    val r = getDependencies.value.flatMap(_.getRules).toList
    logger.debug("getDependencies finished")
    r
  }
  private lazy val isTargetWorldPartRule = Single.option(isTargetWorldPartRules).getOrElse(AnyIsTargetWorldPartRule)
  private lazy val replace = treeAssembler.create(rules,isTargetWorldPartRule.check)
  private lazy val origKeyRegistry = new ActiveOrigKeyRegistry(
    replace.active.collect{ case o: OriginalWorldPart[_] => o.outputWorldKeys }.flatten.toSet
  )
  @provide def getReplace: Seq[Replace] = {
    logger.debug(s"active rules: ${replace.active.size}")
    logger.debug{
      val isActive = replace.active.toSet
      rules.map{ rule =>
        s"\n${if(isActive(rule))"[+]" else "[-]"} ${rule match {
          case r: DataDependencyFrom[_] => s"${r.assembleName} ${r.name}"
          case r: DataDependencyTo[_] => s"out ${r.outputWorldKeys}"
        }}"
      }.toString
    }
    Seq(replace)
  }
  @provide def getActiveOrigKeyRegistry: Seq[ActiveOrigKeyRegistry] =
    Seq(origKeyRegistry)
}

abstract class IsTargetWorldPartRule {
  def check(rule: WorldPartRule): Boolean
}

object AnyIsTargetWorldPartRule extends IsTargetWorldPartRule {
  def check(rule: WorldPartRule): Boolean = true
}

case class UniqueIndexMap[K,V](index: Index)(indexUtil: IndexUtil) extends Map[K,V] {
  def updated[B1 >: V](k: K, v: B1): Map[K, B1] = iterator.toMap.updated(k,v)
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"")).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keyIterator(index).map{ k => (k,Single(indexUtil.getValues(index,k,""))).asInstanceOf[(K,V)] }
  def removed(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keyIterator(index).asInstanceOf[Iterator[K]] // to work with non-Single
  //override def keySet: Set[K] = indexUtil.keySet(index).asInstanceOf[Set[K]] // to get keys from index
}



@c4("RichDataCompApp") final class DynamicByPKImpl(indexUtil: IndexUtil) extends DynamicByPK {
  def get(joinKey: AssembledKey, context: AssembledContext): Map[SrcId,Product] = {
    val index: Index = joinKey.of(context.assembled)
    UniqueIndexMap(index)(indexUtil)
  }
}

@c4multi("RichDataCompApp") final case class GetByPKImpl[V<:Product](typeKey: TypeKey)(
  dynamic: DynamicByPK,
  needAssembledKeyRegistry: NeedAssembledKeyRegistry,
)(
  joinKey: AssembledKey = needAssembledKeyRegistry.toAssembleKey(typeKey),
) extends GetByPK[V] {
  def cl: Class[V] = typeKey.cl.asInstanceOf[Class[V]]
  def ofA(context: AssembledContext): Map[SrcId,V] =
    dynamic.get(joinKey,context).asInstanceOf[Map[SrcId,V]]
}

@c4("RichDataCompApp") final class GetByPKUtil(keyFactory: KeyFactory) {
  def toAssembleKey(vTypeKey: TypeKey): AssembledKey = {
    vTypeKey.args match {
      case Seq() =>
        keyFactory.rawKey(vTypeKey.clName)
      case Seq(arg) if arg.args.isEmpty =>
        keyFactory.rawKey(vTypeKey.clName + '[' + arg.clName + ']')
      case _ => throw new Exception(s"$vTypeKey not implemented") // todo: ? JoinKey to contain TypeKey-s
    }
  }
}
@c4("RichDataCompApp") final class GetByPKComponentFactoryProvider(
  getByPKImplFactory: GetByPKImplFactory
) {
  @provide def get: Seq[ComponentFactory[GetByPK[_]]] =
    List(args=>List(getByPKImplFactory.create(Single(args))))
}

@c4("RichDataCompApp") final class NeedAssembledKeyRegistry(
  util: GetByPKUtil, componentRegistry: ComponentRegistry, config: ListConfig,
)(
  disableCheck: Boolean = config.get("C4NO_INDEX_CHECK").nonEmpty
)(
  classNames: Set[String] = if(disableCheck) Set() else Set(classOf[GetByPK[_]].getName) // can be extended later
)(
  val getRules: List[NeedWorldPartRule] = for{
    component <- componentRegistry.components.toList
    inKey <- component.in if classNames(inKey.clName)
  } yield new NeedWorldPartRule(List(util.toAssembleKey(Single(inKey.args))), component.out.clName)
)(
  values: Set[AssembledKey] = getRules.flatMap(_.inputWorldKeys).toSet
) extends DataDependencyProvider {
  def toAssembleKey(typeKey: TypeKey): AssembledKey = {
    val joinKey = util.toAssembleKey(typeKey)
    assert(values(joinKey) || disableCheck, s"no need byPK self check: $joinKey")
    joinKey
  }
}

class NeedWorldPartRule(
  val inputWorldKeys: List[AssembledKey], val name: String
) extends WorldPartRule with DataDependencyFrom[Index] {
  def assembleName: String = "Tx"
}

@c4("SkipWorldPartsApp") final class IsTargetWorldPartRuleImpl extends IsTargetWorldPartRule {
  def check(rule: WorldPartRule): Boolean = rule.isInstanceOf[NeedWorldPartRule]
}
