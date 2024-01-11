package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.{Tagged, TaskPos}
import ee.cone.c4assemble.SchedulerConf._
import ee.cone.c4assemble.Types.{Index, emptyIndex}
import ee.cone.c4di.{c4, c4multi}

import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.{LinkedBlockingQueue, RecursiveTask}
import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Try

object SchedulerConf {
  trait WorldPosTag
  type WorldPos = Int with Tagged[WorldPosTag]
  trait InputPosTag
  type InputPos = Int with Tagged[InputPosTag]

  case class InputConf(was: Boolean, worldPos: WorldPos, inputPos: InputPos)
  sealed trait TaskConf
  case class CalcTaskConf(joinPos: Int, inputs: ArraySeq[InputConf], outWorldPos: ArraySeq[WorldPos]) extends TaskConf
  case class BuildTaskConf(
    planNotifyWorldPos: ArraySeq[WorldPos], planNotifyInputPos: ArraySeq[InputPos],
    worldPos: WorldPos, outDiffPos: ArraySeq[InputPos]
  ) extends TaskConf
}
final class ConfIIMap[K<:Int,V<:Int](data: Array[V]){
  def apply(k: K): V = data(k)
}

final class SchedulerConf(
  val tasks: ArraySeq[TaskConf],
  val subscribedWorldPos: ConfIIMap[WorldPos,TaskPos], val subscribedInputPos: ConfIIMap[InputPos,TaskPos],
  val worldPosFromKey: Map[JoinKey,WorldPos], val worldKeys: ArraySeq[JoinKey], val inputs: ArraySeq[(Int,Int)],
  val plannerConf: PlannerConf, val emptyReadModel: ReadModel
)

@c4("AssembleApp") final class SchedulerFactoryImpl(
  expressionsDumpers: List[ExpressionsDumper[Unit]],
  schedulerImplFactory: SchedulerImplFactory, plannerFactory: PlannerFactory
) extends SchedulerFactory {
  def create(rulesByPriority: Seq[WorldPartRule]): Replace = {
    val rulesByPriorityArr = ArraySeq.from(rulesByPriority)
    val joins = rulesByPriorityArr.collect { case e: Join => e }
    expressionsDumpers.foreach(_.dump(joins.toList))
    val worldKeys = rulesByPriorityArr.collect { case e: DataDependencyTo[_] => e.outputWorldKeys }.flatten
      .map { case k: JoinKey => k }.distinct
    val worldPosFromKey = worldKeys.zipWithIndex.toMap.asInstanceOf[Map[JoinKey,WorldPos]]
    val inputs = joins.zipWithIndex.flatMap{ case (join,joinPos) => join.inputWorldKeys.indices.map((joinPos,_)) }
    val inputPosFromLocal = inputs.zipWithIndex.toMap.asInstanceOf[Map[(Int,Int),InputPos]]
    val calcTasks = ArraySeq.from(joins.zipWithIndex.map{ case (join,joinPos) =>
      val inputs = ArraySeq.from(join.inputWorldKeys.zipWithIndex.map { case (k:JoinKey,inputPos) =>
        InputConf(k.was, worldPosFromKey(k.withWas(false)), inputPosFromLocal((joinPos, inputPos)))
      })
      val outputs = ArraySeq.from(join.outputWorldKeys.map{ case k: JoinKey => assert(!k.was); worldPosFromKey(k) })
      CalcTaskConf(joinPos, inputs, outputs)
    })
    val buildTasks = {
      // we need additional deps for planner for looped calculations: from was out build to other output builds, to prevent output-build start while looping
      val wasPairedPos =
        joins.flatMap(_.inputWorldKeys.collect { case k: JoinKey if k.was => worldPosFromKey(k.withWas(false)) })
          .toSet
      val crossOutputDeps = (for {
        t <- calcTasks
        pairedPos <- t.outWorldPos if wasPairedPos(pairedPos)
        k <- t.outWorldPos if k != pairedPos
      } yield pairedPos -> k).groupMap(_._1)(_._2).withDefaultValue(ArraySeq.empty)
      //println(s"crossOutputDeps: ${crossOutputDeps}")
      val calcInputsByWorldPos = calcTasks.flatMap(_.inputs).groupBy(_.worldPos).withDefaultValue(ArraySeq.empty)
      worldKeys.indices.map(_.asInstanceOf[WorldPos]).map{ wPos =>
        val outputs = calcInputsByWorldPos(wPos)
        val planNotifyInputPos = outputs.filterNot(_.was).map(_.inputPos)
        BuildTaskConf(crossOutputDeps(wPos), planNotifyInputPos, wPos, outputs.map(_.inputPos))
      }
    }
    val tasks = ArraySeq.from(buildTasks ++ calcTasks)
    val subscribedInputPos = new ConfIIMap[InputPos,TaskPos](chk(inputs.size, tasks.zipWithIndex.collect{
      case (t: CalcTaskConf, taskPos) => t.inputs.map(_.inputPos->taskPos.asInstanceOf[TaskPos])
    }.flatten.sorted).toArray)
    val subscribedWorldPos = new ConfIIMap[WorldPos,TaskPos](chk(worldKeys.size, tasks.zipWithIndex.collect{
      case (t: BuildTaskConf, taskPos) => t.worldPos -> taskPos.asInstanceOf[TaskPos]
    }.sorted).toArray)
    val planConf = plannerFactory.createConf(tasks.map(t => PlanTaskConf(t match {
      case t: CalcTaskConf => t.outWorldPos.map(subscribedWorldPos(_))
      case t: BuildTaskConf =>
        t.planNotifyInputPos.map(subscribedInputPos(_)) ++ t.planNotifyWorldPos.map(subscribedWorldPos(_))
    })))
    val emptyReadModel = {
      val emptyWorld = ImmArr.empty[WorldPos,RIndex](emptyIndex, new Array(_), new Array(_))
      val emptyInputs = ImmArr.empty[InputPos,RIndex](emptyIndex, new Array(_), new Array(_))
      val spentNs = ImmArr.empty[TaskPos,java.lang.Long](0L, new Array(_), new Array(_))
      new ReadModelImpl(emptyWorld, emptyInputs, emptyInputs, worldPosFromKey, spentNs)
    }
    val conf = new SchedulerConf(
      tasks, subscribedWorldPos, subscribedInputPos, worldPosFromKey, worldKeys, inputs, planConf, emptyReadModel
    )
    //new Thread(new ThreadTracker).start()
    schedulerImplFactory.create(rulesByPriority, conf, joins)
  }
  private def chk[K<:Int,V<:Int](size: Int, pairs: Seq[(K,V)]): Seq[V] = {
    assert(pairs.map(_._1) == (0 until size))
    pairs.map(_._2)
  }
}

object ParallelExecution {
  val shortEC: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = ExecutionContext.defaultReporter(cause)
  }
  def seq[T](tasks: Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[T]] = Future.sequence(tasks)
}


trait ParallelExecution {
  def execute[S, T<:Object, U](tasks: Array[S], calc: S => T, create: Int=>Array[T], aggr: Array[T] => U): U
}

/*@c4("AssembleApp") final class ParallelExecutionImpl() extends ParallelExecution {
  import ParallelExecution._
  def execute[S, T<:Object, U](tasks: IndexedSeq[S], calc: S => T, aggr: Seq[T] => U, ec: ExecutionContext): Future[U] = {
    val (seqTasks, parTasks) = tasks.splitAt(1)
    val bsFO = parTasks.size match {
      case 0 => None
      case 1 => Option(Future(parTasks.map(calc))(ec))
      case _ => Option(seq(parTasks.map(b => Future(calc(b))(ec)))(shortEC))
    }
    val asR = seqTasks.map(calc)
    bsFO.fold(Future.successful(aggr(asR)))(_.map(bsR => aggr(asR ++ bsR))(shortEC))
  }
}*/

/**/@c4("AssembleApp") final class FJPParallelExecutionImpl() extends ParallelExecution {
  private final class LTask[S,T](task: S, calc: S => T) extends RecursiveTask[T] {
    def compute(): T = calc(task)
  }

  def execute[S, T<:Object, U](tasks: Array[S], calc: S => T, create: Int=>Array[T], aggr: Array[T] => U): U = {
    val lTasks = new Array[LTask[S,T]](tasks.length)
    var i = lTasks.length
    while(i > 0){
      i -= 1
      val lTask  = new LTask(tasks(i), calc)
      lTasks(i) = lTask
      if(i > 0) lTask.fork() else lTask.invoke()
    }
    val resA = create(lTasks.length)
    while(i < lTasks.length){
      resA(i) = lTasks(i).join()
      i += 1
    }
    aggr(resA)
  }
}

@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
@c4multi("AssembleApp") final class SchedulerImpl(
  val active: Seq[WorldPartRule], conf: SchedulerConf, joins: Seq[Join]
)(
  indexUtil: IndexUtil, rIndexUtil: RIndexUtil, plannerFactory: PlannerFactory, parallelExecution: ParallelExecution,
  profiling: RAssProfiling,
)(
  emptyCalculated: Array[Array[RIndexPair]] = Array.empty
)(
  emptyCalculatedByBuildTask: ImmArr[WorldPos, Array[Array[RIndexPair]]] =
    ImmArr.empty(emptyCalculated, new Array(_), new Array(_))
) extends Replace {
  import ParallelExecution._
  import parallelExecution._

  private trait Ev {
    def profilingCounts: AbstractProfilingCounts
  }

  private def zip[A, B](a: ArraySeq[A], b: Seq[B]) = {
    assert(a.size == b.size)
    a.zip(b)
  }



  private def addHandler(transJoin: TransJoin, dir: Int, inputs: Seq[Index], res: List[KeyIterationHandler]): List[KeyIterationHandler] =
    if(inputs.forall(indexUtil.isEmpty)) res else transJoin.dirJoin(dir, inputs) :: res

  private type RIndexPairs = Array[Array[RIndexPair]]
  private class MutableSchedulingContext(
    val planner: MutablePlanner,
    val model: COWArr[WorldPos,Index], val inputDiffs: COWArr[InputPos,Index], val inputPrevValues: COWArr[InputPos,Index],
    val calculatedByBuildTask: COWArr[WorldPos,RIndexPairs],
    val profilingCountsList: util.ArrayList[(TaskPos,AbstractProfilingCounts)],
  )

  private def startBuild(context: MutableSchedulingContext, taskConf: BuildTaskConf): Option[()=>Ev] = {
    val calculated = context.calculatedByBuildTask(taskConf.worldPos)
    if(calculated.length == 0) None else {
      context.calculatedByBuildTask(taskConf.worldPos) = emptyCalculated
      val len = taskConf.outDiffPos.length
      val prev = new Array[Index](1 + len)
      prev(0) = context.model(taskConf.worldPos)
      var i = 0
      while(i < len){
        prev(i + 1) = context.inputDiffs(taskConf.outDiffPos(i))
        i += 1
      }
      Option(()=>continueBuild(taskConf, calculated, prev))
    }
  }

  private def continueBuild(taskConf: BuildTaskConf, calculated: RIndexPairs, prev: Array[Index]): Ev = {
    val prevDistinct = prev.distinct
    val task = indexUtil.buildIndex(prevDistinct, calculated)
    val (nextDistinct, profilingCounts) = execute[IndexingSubTask,IndexingResult,(Seq[Index],MergeProfilingCounts)](
      task.subTasks.toArray, rIndexUtil.execute, new Array(_), rIndexUtil.merge(task, _)
    )
    val next = prev.map(zip(ArraySeq.from(prevDistinct), nextDistinct).toMap)
    new BuiltEv(taskConf, prev, next, profilingCounts)
  }
  private class BuiltEv(
    val taskConf: BuildTaskConf, val prev: Array[Index], val next: Array[Index],
    val profilingCounts: MergeProfilingCounts
  ) extends Ev
  private def replace[K<:Int](values: COWArr[K,Index], pos: K, prev: Index, next: Index): Unit = {
    assert(values(pos)==prev)
    values(pos) = next
  }
  private def finishBuild(context: MutableSchedulingContext, ev: BuiltEv): Unit = {
    replace(context.model, ev.taskConf.worldPos, ev.prev(0), ev.next(0))
    val len = ev.taskConf.outDiffPos.length
    var i = 0
    while(i < len) {
      val pos = ev.taskConf.outDiffPos(i)
      replace(context.inputDiffs, pos, ev.prev(i+1), ev.next(i+1))
      context.planner.setTodo(conf.subscribedInputPos(pos), value = true)
      i += 1
    }
  }

  private def startCalc(context: MutableSchedulingContext, taskConf: CalcTaskConf): Option[()=>Ev] = {
    var nonEmpty = false
    val len = taskConf.inputs.length
    val diffs = new Array[Index](len)
    val prevInputs = new Array[Index](len)
    val inputs = new Array[Index](len)
    var i = 0
    while(i < len){
      val inputPos = taskConf.inputs(i).inputPos
      val worldPos = taskConf.inputs(i).worldPos
      val diff = context.inputDiffs(inputPos)
      val v = context.model(worldPos)
      diffs(i) = diff
      inputs(i) = v
      prevInputs(i) = context.inputPrevValues(inputPos)
      context.inputPrevValues(inputPos) = v
      context.inputDiffs(inputPos) = emptyIndex
      if(!indexUtil.isEmpty(diff)) nonEmpty = true
      i += 1
    }
    if(nonEmpty) Option(()=>continueCalc(new CalcReq(taskConf, prevInputs, inputs, diffs)))
    else None
  }

  private class CalcReq(
    val taskConf: CalcTaskConf, val prevInputValues: Seq[Index], val nextInputValues: Seq[Index], val inputDiffs: Seq[Index]
  )
  private def continueCalc(req: CalcReq): Ev = {
    val transJoin = joins(req.taskConf.joinPos).joins(req.inputDiffs)
    val handlers =
      addHandler(transJoin, -1, req.prevInputValues, addHandler(transJoin, +1, req.nextInputValues, Nil)).toArray
    // so if invalidateKeysFromIndexes for handlers are same, then it's normal diff case, else `byEq` changes, and we need to recalc all
    val aggr = handlers match {
      case Array(a, b) if a.invalidateKeysFromIndexes == b.invalidateKeysFromIndexes =>
        execute[RecalculationTask, AggrDOut, AggrDOut](
          rIndexUtil.recalculate(a.invalidateKeysFromIndexes.toArray),
          { rTask =>
            val buffer = indexUtil.createBuffer()
            rIndexUtil.execute(rTask, k => {
              a.handle(k, buffer)
              b.handle(k, buffer)
            })
            indexUtil.aggregate(buffer)
          },
          new Array(_), indexUtil.aggregate
        )
      case hs =>
        execute[(RecalculationTask, KeyIterationHandler), AggrDOut, AggrDOut](
          hs.flatMap(h => rIndexUtil.recalculate(h.invalidateKeysFromIndexes.toArray).map((_, h))),
          { case (rTask, handler) =>
            val buffer = indexUtil.createBuffer()
            rIndexUtil.execute(rTask, k => handler.handle(k, buffer))
            indexUtil.aggregate(buffer)
          },
          new Array(_), indexUtil.aggregate
        )
    }
    val diff =
      req.taskConf.outWorldPos.indices.toArray.map(i => req.taskConf.outWorldPos(i) -> indexUtil.byOutput(aggr, i))
      .filter(_._2.nonEmpty)
    new CalculatedEv(diff, indexUtil.countResults(aggr))
  }
  private class CalculatedEv(val diff: Array[(WorldPos, RIndexPairs)], val profilingCounts: ProfilingCounts) extends Ev
  private def finishCalc(context: MutableSchedulingContext, ev: CalculatedEv): Unit = setTodoBuild(context, ev.diff)

  private def setTodoBuild(context: MutableSchedulingContext, diff: Array[(WorldPos, RIndexPairs)]): Unit = {
    val c = context.calculatedByBuildTask
    var i = 0
    while(i < diff.length) {
      val k = diff(i)._1
      val v = diff(i)._2
      val was = c(k)
      c(k) = if(was.length == 0) v else {
        val merged = new Array[Array[RIndexPair]](was.length+v.length)
        System.arraycopy(was,0,merged,0,was.length)
        System.arraycopy(v,0,merged,was.length,v.length)
        merged
      }
      context.planner.setTodo(conf.subscribedWorldPos(k), value = true)
      i += 1
    }
  }

  private def cowArrDebug[K<:Int,V](hint: String, explainK: K=>String, explainV: V=>String): Option[CowArrDebug[K, V]] =
    Option(new CowArrDebug[K, V] {
      def update(pos: K, value: V): Unit = profiling.warn(s"$hint ${explainK(pos)} ${explainV(value)}") //report
    })
  private def explainJoinPos(joinPos: Int): String =
    joins(joinPos) match { case j => s"${j.assembleName} rule ${j.name}" }
  private def explainWorldPos(pos: WorldPos): String = conf.worldKeys(pos).toString
  private def explainInputPos(pos: InputPos): String = {
    val (joinPos, iPos) = conf.inputs(pos)
    s"${explainJoinPos(joinPos)} input #$iPos"
  }
  private def explainIndex(index: Index): String = s"${rIndexUtil.keyCount(index)} keys"
  private def explainQueue(queue: RIndexPairs): String = s"${queue.map(_.length).sum} pairs"
  private def explainTask(exprPos: TaskPos): String = conf.tasks(exprPos) match {
    case t: CalcTaskConf =>
      val j = joins(t.joinPos)
      s"#$exprPos calc ${j.assembleName} rule ${j.name}"
    case t: BuildTaskConf => s"#$exprPos build ${conf.worldKeys(t.worldPos)}"
  }
  private def reportPlanning(hint: String, exprPos: TaskPos): Unit = profiling.warn(s"$hint ${explainTask(exprPos)}")

  private def reportTop(context: MutableSchedulingContext, period: Long) = {
    val txName = Thread.currentThread.getName
    profiling.warn(s"long join $period ms by $txName")
    context.profilingCountsList.asScala.groupMapReduce(_._1)(_._2)((a, b) => (a, b) match {
      case (a: ProfilingCounts, b: ProfilingCounts) => ProfilingCounts(
        resultCount = a.resultCount + b.resultCount, maxNs = Math.max(a.maxNs, b.maxNs),
        callCount = a.callCount + b.callCount, spentNs = a.spentNs + b.spentNs
      )
      case (a: MergeProfilingCounts, b: MergeProfilingCounts) => MergeProfilingCounts(
        maxNs = Math.max(a.maxNs, b.maxNs), spentNs = a.spentNs + b.spentNs
      )
    }).toSeq.sortBy(_._2.maxNs).takeRight(64).foreach { case (exprPos, profilingCounts) =>
      val countsStr = profilingCounts match {
        case a: ProfilingCounts =>
          s"max ${nsToMilli(a.maxNs)} ms, sum ${nsToMilli(a.spentNs)} ms, ${a.callCount} calls, ${a.resultCount} results"
        case a: MergeProfilingCounts =>
          s"max ${nsToMilli(a.maxNs)} ms, sum ${nsToMilli(a.spentNs)} ms"
      }
      profiling.warn(s"$countsStr -- ${explainTask(exprPos)}")
    }
    //.toArray(Array.empty)
  }
  private def reportTopOpt(context: MutableSchedulingContext, period: Long) = {
    profiling.debug(()=>s"was joining for $period ms")
    if(period > profiling.msWarnPeriod) reportTop(context, period)
  }
  private def addSpent(context: MutableSchedulingContext, spentNs: ImmArr[TaskPos,java.lang.Long]): ImmArr[TaskPos,java.lang.Long] = {
    val spentNsM = spentNs.toMutable(None)
    context.profilingCountsList.forEach { case (exprPos, profilingCounts) =>
      spentNsM(exprPos) = spentNsM(exprPos) + profilingCounts.maxNs // .spentNs if we care of cpu
    }
    spentNsM.toImmutable
  }
  private def nsToMilli(ns: Long) = ns / 1000000

  def replace(model: ReadModel, diff: Diffs, executionContext: OuterExecutionContext): ReadModel = {
    val startedAt = System.nanoTime
    val planner = plannerFactory.createMutablePlanner(conf.plannerConf)
    val modelImpl = model match{ case m: ReadModelImpl => m }
    val noDbg = !DebugCounter.on(0)
    val context = new MutableSchedulingContext(
      if(noDbg) planner else new DebuggingPlanner(planner, reportPlanning),
      modelImpl.model.toMutable(if(noDbg) None else cowArrDebug("set-model",explainWorldPos,explainIndex)),
      modelImpl.inputDiffs.toMutable(if(noDbg) None else cowArrDebug("set-input-diff",explainInputPos,explainIndex)),
      modelImpl.inputPrevValues.toMutable(if(noDbg) None else cowArrDebug("set-input-prev",explainInputPos,explainIndex)),
      emptyCalculatedByBuildTask.toMutable(if(noDbg) None else cowArrDebug("set-queue",explainWorldPos,explainQueue)),
      new util.ArrayList
    )
    setTodoBuild(context, diff.map{ case (k: JoinKey, v) => (conf.worldPosFromKey(k),v) }.toArray)
    loop(context, executionContext.value)
    context.calculatedByBuildTask.requireIsEmpty()
    context.inputDiffs.requireIsEmpty()
    reportTopOpt(context, nsToMilli(System.nanoTime - startedAt))
    new ReadModelImpl(
      context.model.toImmutable, context.inputDiffs.toImmutable, context.inputPrevValues.toImmutable,
      conf.worldPosFromKey, addSpent(context, modelImpl.spentNs)
    )
  }
  private final class OuterEv(val exprPos: TaskPos, val event: Ev)
  private def loop(context: MutableSchedulingContext, ec: ExecutionContext): Unit = {
    val queue = new LinkedBlockingQueue[Try[OuterEv]]
    val planner = context.planner
    while(planner.planCount > 0) if(planner.suggestedNonEmpty) {
      val exprPos = planner.suggestedHead
      (conf.tasks(exprPos) match {
        case taskConf: BuildTaskConf => startBuild(context, taskConf)
        case taskConf: CalcTaskConf => startCalc(context, taskConf)
      }) match {
        case None => ()
        case Some(f) =>
          planner.setStarted(exprPos, value = true)
          Future(new OuterEv(exprPos,f()))(ec).onComplete{ tEv => queue.put(tEv) }(shortEC)
      }
      planner.setTodo(exprPos, value = false)
    } else {
      val outerEv: OuterEv = queue.take().get
      outerEv.event match {
        case ev: CalculatedEv => finishCalc(context, ev)
        case ev: BuiltEv => finishBuild(context, ev)
      }
      planner.setStarted(outerEv.exprPos, value = false)
      addProfilingCounts(context, outerEv.exprPos, outerEv.event.profilingCounts)
    }
  }
  private def addProfilingCounts(
    context: MutableSchedulingContext, pos: TaskPos, profilingCounts: AbstractProfilingCounts
  ): Unit = context.profilingCountsList.add((pos, profilingCounts))
  def emptyReadModel: ReadModel = conf.emptyReadModel

  def report(model: ReadModel): Unit = {
    val modelImpl = model match{ case m: ReadModelImpl => m }
//    for{
//      exprPos <- conf.tasks.indices.asInstanceOf[IndexedSeq[TaskPos]]
//      ms = nsToMilli(modelImpl.spentNs(exprPos)) if ms > 0L
//    } profiling.warn(s"aggr $ms ms ${explainTask(exprPos)}")
    for {
      (ns,exprPos) <- (for{
        exprPos <- conf.tasks.indices.asInstanceOf[IndexedSeq[TaskPos]]
        ns = modelImpl.spentNs(exprPos)
      } yield (ns,exprPos)).sorted.takeRight(128)
      ms = nsToMilli(ns) if ms > 0L
    } profiling.warn(s"aggr $ms ms ${explainTask(exprPos)}")
  }
}

class ReadModelImpl(
  val model: ImmArr[WorldPos,Index], val inputDiffs: ImmArr[InputPos,Index], val inputPrevValues: ImmArr[InputPos,Index],
  val worldPosFromKey: Map[JoinKey,WorldPos], val spentNs: ImmArr[TaskPos,java.lang.Long],
) extends ReadModel {
  def getIndex(key: AssembledKey): Option[Index] = key match {
    case k: JoinKey => worldPosFromKey.get(k).map(model(_))
    case _ => None
  }
}

class ReadModelMap(model: ReadModelImpl) extends Map[AssembledKey,Index] {
  def removed(key: AssembledKey): Map[AssembledKey, Index] = throw new Exception("not supported")
  def updated[V1 >: Index](key: AssembledKey, value: V1): Map[AssembledKey, V1] = throw new Exception("not supported")
  def get(key: AssembledKey): Option[Index] = model.getIndex(key)
  def iterator: Iterator[(AssembledKey, Index)] = model.worldPosFromKey.keysIterator.map(k=>k->apply(k))
}

@c4("AssembleApp") final class ReadModelUtilImpl() extends ReadModelUtil {
  def toMap: ReadModel=>Map[AssembledKey,Index] = { case model: ReadModelImpl => new ReadModelMap(model) }
}
// emptyIndex

object ImmArr{
  val innerPower: Int = 9
  val outerSize: Int = 64
  private val innerSize: Int = 1 << innerPower
  val innerMask: Int = innerSize - 1

  def empty[K <: Int, V <: Object](value: V, createInnerArray: Int => Array[V], createOuterArray: Int => Array[Array[V]]): ImmArr[K, V] = {
    val inner = createInnerArray(innerSize)
    java.util.Arrays.setAll[V](inner, (_: Int) => value)
    val outer = createOuterArray(outerSize)
    java.util.Arrays.setAll[Array[V]](outer, (_: Int) => inner)
    new ImmArr(outer, 0, value)
  }
}
final class ImmArr[K<:Int,V<:Object](data: Array[Array[V]], nonEmptyCount: Int, emptyItem: V){
  def apply(pos: K): V = data(pos >> ImmArr.innerPower)(pos & ImmArr.innerMask)
  def toMutable(debug: Option[CowArrDebug[K,V]]): COWArr[K,V] = new COWArr(data, nonEmptyCount, emptyItem, debug)
}
final class COWArr[K<:Int,V<:Object](private var data: Array[Array[V]], private var nonEmptyCount: Int, emptyItem: V, debug: Option[CowArrDebug[K,V]]){
  def apply(pos: K): V = data(pos >> ImmArr.innerPower)(pos & ImmArr.innerMask)
  private var privateC: Long = 0L
  def update(pos: K, value: V): Unit = {
    require(pos >= 0)
    import ImmArr._
    val outerPos = pos >> innerPower
    require(outerPos < outerSize)
    if(privateC == 0){
      data = data.clone()
    }
    val shifted = 1L << outerPos
    if((privateC & shifted) == 0L){
      data(outerPos) = data(outerPos).clone()
      privateC = privateC | shifted
    }
    val part = data(outerPos)
    val innerPos = pos & innerMask
    nonEmptyCount += (if(part(innerPos) ne emptyItem) -1 else 0) + (if(value ne emptyItem) 1 else 0)
    part(innerPos) = value
    if(debug ne None) debug.get(pos) = value
  }
  def toImmutable: ImmArr[K,V] = {
    privateC = 0L
    new ImmArr(data, nonEmptyCount, emptyItem)
  }
  def requireIsEmpty(): Unit = assert(nonEmptyCount == 0)
}

sealed trait CowArrDebug[K<:Int,V] {
  def update(pos: K, value: V): Unit
}

class DebuggingPlanner(inner: MutablePlanner, report: (String,TaskPos)=>Unit) extends MutablePlanner {
  private def bStr(value: Boolean): String = if(value) "on " else "off"
  def setTodo(exprPos: TaskPos, value: Boolean): Unit = {
    report(s"setTodo    ${bStr(value)}",exprPos)
    inner.setTodo(exprPos,value)
  }
  def setStarted(exprPos: TaskPos, value: Boolean): Unit = {
    report(s"setStarted ${bStr(value)}",exprPos)
    inner.setStarted(exprPos,value)
  }
  def suggestedNonEmpty: Boolean = inner.suggestedNonEmpty
  def suggestedHead: TaskPos = inner.suggestedHead
  def planCount: Int = inner.planCount
}

class ThreadTracker extends Runnable {
  def run(): Unit = {
    val man = ManagementFactory.getThreadMXBean
    while(true){
      val assThreads = man.dumpAllThreads(false, false)
        .filter(ti => ti.getThreadName.contains("ass-") && ti.getThreadState == Thread.State.RUNNABLE)
      println(s"assCount ${assThreads.length}")
      if(assThreads.length > 2 && assThreads.length < 12) for(ti <- assThreads){
        println("ass")
        for(s <- ti.getStackTrace) println(s.toString)
      }
      Thread.sleep(1000)
    }
  }
}

/*
plan/ideas:
conc-y vs par-m -- is cpu busy
  gather stats on joiners then use next time
All? par Each
fjp
chk wrld hash long
if value-count >> key-count (and it's not Values?), then segmented bucketPos based on both
*/