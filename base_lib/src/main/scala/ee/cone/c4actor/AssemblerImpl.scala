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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.annotation.tailrec
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

@c4("RichDataCompApp") final class RAssProfilingImpl(
  listConfig: ListConfig,
  assembleStatsAccumulator: AssembleStatsAccumulatorImpl,
)(
  val msWarnPeriod: Long = Single.option(listConfig.get("C4ASSEMBLE_WARN_PERIOD_MS")).fold(1000L)(_.toLong)
) extends RAssProfiling with LazyLogging {
  def warn(content: String): Unit = logger.warn(content)
  def debug(content: ()=>String): Unit =  logger.debug(s"$id ${content()}")
  private def id = s"T-${Thread.currentThread.getId}"
  def addPeriod(accId: Int, value: Long): Unit = assembleStatsAccumulator.add(accId, value)
}

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
  arrayUtil: ArrayUtil,
  replace: Replace,
  activeOrigKeyRegistry: ActiveOrigKeyRegistry,
  origPartitionerRegistry: OrigPartitionerRegistry,
) extends LazyLogging {
  def seq[T](s: Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[T]] = Future.sequence(s)
  def toTreeReplace(
    assembled: ReadModel, updates: Seq[N_Update], executionContext: OuterExecutionContext,
    profilingContext: RAssProfilingContext
  ): ReadModel = {
    //val end = NanoTimer()
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
    val tasks = arrayUtil.spread(updatesArr, updatesArr.length, SpreadUpdates.partCount, SpreadUpdates)
      .filter(_.length>0).sortBy(-_.length)
    val taskResultsF = seq(tasks.map{ part => Future{ handle(part) }(ec) })(parasitic)
    val taskResults = Await.result(taskResultsF, Duration.Inf)
    val diff = taskResults.flatten.groupMap(_._1)(_._2).transform((_,v)=>v.toArray.flatten).toSeq
    //assert(diff.map(_._1).distinct.size == diff.size)
    logger.debug("toTreeReplace indexGroups after")
    replace.replace(assembled,diff,executionContext,profilingContext)
    //val period = end.ms
//    if(logger.underlying.isDebugEnabled){
//      val ids = updates.map(_.valueTypeId).distinct.map(v=>s"0x${java.lang.Long.toHexString(v)}").mkString(" ")
//      logger.debug(s"checked: ${transition.taskLog.size} rules by $txName ($ids)")
//    }
  }
}

class AssemblerProfiling extends LazyLogging {
  def id = s"T-${Thread.currentThread.getId}"
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

@c4("RichDataCompApp") final class AssembleStatsAccumulatorImpl extends AssembleStatsAccumulator {
  private val sumRefs = Seq(new AtomicLong(0L), new AtomicLong(0L))
  private val maxRefs = Seq[AtomicReference[List[(Long,Long)]]](new AtomicReference(Nil), new AtomicReference(Nil))
  def add(id: Int, value: Long): Unit = {
    sumRefs(id).addAndGet(value)
    addMax(id, value)
  }
  @tailrec private def addMax(id: Int, value: Long): Unit = {
    val ref = maxRefs(id)
    val was = ref.get()
    val now = System.nanoTime
    val will = (now, value) :: was.dropWhile{ case (_,v) => v <= value }
    if (!ref.compareAndSet(was, will)) addMax(id, value)
  }
  @tailrec private def getMax(id: Int): Long = {
    val ref = maxRefs(id)
    val was = ref.get()
    val now = System.nanoTime
    val will = was.takeWhile{ case (t,_) => now - t < 300_000_000_000L }
    if(!ref.compareAndSet(was, will)) getMax(id) else will.lastOption.fold(0L){ case (_,v) => v }
  }
  def report(): List[(String,Int,Long)] =
    List(0,1).flatMap(id => List(("assemble_sum", id, sumRefs(id).get()), ("assemble_max", id, getMax(id))))
}

@c4("RichDataCompApp") final class MemLog(config: ListConfig) extends Executable with Early with LazyLogging {
  def run(): Unit = {
    val runtime = Runtime.getRuntime
    def iter(): Unit = {
      logger.info(s"${runtime.totalMemory - runtime.freeMemory} bytes used")
      Thread.sleep(1000)
      iter()
    }
    if(config.get("C4ASSEMBLE_DEBUG_TXS").nonEmpty) iter()
  }
}

@c4("RichDataCompApp") final class ReadModelAddImpl(
  utilOpt: DeferredSeq[AssemblerUtil],
  toUpdate: ToUpdate,
  actorName: ActorName,
  catchNonFatal: CatchNonFatal,
  config: ListConfig,
)(
  debugTxs: Option[String] = Single.option(config.get("C4ASSEMBLE_DEBUG_TXS"))
) extends ReadModelAdd with LazyLogging {
  // read model part:
  private def util = Single(utilOpt.value)
  private def offset(events: Seq[RawEvent]): List[N_Update] = for{
    ev <- events.lastOption.toList
    lEvent <- LEvent.update(S_Offset(actorName.value,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  def add(executionContext: OuterExecutionContext, events: Seq[RawEvent]): ReadModel=>ReadModel = assembled => catchNonFatal {
    logger.debug("starting toUpdate")
    val updates: List[N_Update] = offset(events) ::: toUpdate.toUpdates(events.toList,"rma").map(toUpdate.toUpdateLost)
    logger.debug("done toUpdate")
    val eventIds = events.map(_.srcId)
    (new AssemblerProfiling).debugOffsets("starts-reducing", eventIds)
    val debug = debugTxs.exists(cfTxs=>eventIds.exists(id=>id.contains(cfTxs)||cfTxs.contains(id)))
    util.toTreeReplace(assembled, updates, executionContext, RAssProfilingContext(0, eventIds, debug))
  }("reduce"){ e => // ??? exception to record
    if(events.size == 1){
      val updates = offset(events) ++
        events.map(ev=>S_FailedUpdates(ev.srcId, e.getMessage))
          .flatMap(LEvent.update).map(toUpdate.toUpdate)
      util.toTreeReplace(assembled, updates, executionContext, RAssProfilingContext(0, Nil, needDetailed = false))
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
    val profilingContext = RAssProfilingContext(1, Nil, TxAddAssembleDebugKey.of(local))
    val result = util.toTreeReplace(local.assembled, externalOut, local.executionContext, profilingContext)
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

@c4("RichDataCompApp") final class NonSingleLoggerImpl(
  was: Array[AtomicBoolean] = Array.tabulate(32)(_=>new AtomicBoolean(false))
) extends NonSingleLogger with LazyLogging {
  def warn(a: String, b: String): Unit =
    if(!was(Math.floorMod(a.hashCode, was.length)).getAndSet(true)) logger.warn(s"$a$b")
}