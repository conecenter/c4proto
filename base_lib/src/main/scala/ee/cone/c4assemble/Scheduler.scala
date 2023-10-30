package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.{Tagged, TaskPos}
import ee.cone.c4assemble.RIndexTypes.RIndexKey
import ee.cone.c4assemble.SchedulerConf._
import ee.cone.c4assemble.Types.{Index, emptyIndex}
import ee.cone.c4di.{c4, c4multi}

import java.lang.management.ManagementFactory
import java.util.concurrent.{LinkedBlockingQueue, RecursiveTask}
import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
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
  val worldPosFromKey: Map[JoinKey,WorldPos], val plannerConf: PlannerConf, val emptyReadModel: ReadModel
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
      val emptyWorld = ImmArr.fill[WorldPos,RIndex](emptyIndex, new Array(_), new Array(_))
      val emptyInputs = ImmArr.fill[InputPos,RIndex](emptyIndex, new Array(_), new Array(_))
      new ReadModelImpl(emptyWorld, emptyInputs, emptyInputs, worldPosFromKey)
    }
    val conf = new SchedulerConf(tasks, subscribedWorldPos, subscribedInputPos, worldPosFromKey, planConf, emptyReadModel)
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
  def execute[S, T<:Object, U](tasks: IndexedSeq[S], calc: S => T, create: Int=>Array[T], aggr: Array[T] => U): Future[U]
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

  def execute[S, T<:Object, U](tasks: IndexedSeq[S], calc: S => T, create: Int=>Array[T], aggr: Array[T] => U): Future[U] = {
    val lTasks = new Array[LTask[S,T]](tasks.size)
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
    Future.successful(aggr(resA))
  }
}

@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
@c4multi("AssembleApp") final class SchedulerImpl(
  val active: Seq[WorldPartRule], conf: SchedulerConf, joins: Seq[Join]
)(
  indexUtil: IndexUtil, rIndexUtil: RIndexUtil, plannerFactory: PlannerFactory, parallelExecution: ParallelExecution
)(
  emptyCalculated: Array[Array[RIndexPair]] = Array.empty
)(
  emptyCalculatedByBuildTask: ImmArr[WorldPos, Array[Array[RIndexPair]]] =
    ImmArr.fill(emptyCalculated, new Array(_), new Array(_))
) extends Replace {
  import ParallelExecution._
  import parallelExecution._

  private trait Ev

  private def zip[A, B](a: ArraySeq[A], b: Seq[B]) = {
    assert(a.size == b.size)
    a.zip(b)
  }

  private class CalcReq(
    val taskConf: CalcTaskConf, val prevInputValues: Seq[Index], val nextInputValues: Seq[Index], val inputDiffs: Seq[Index]
  )

  private def execute2[OuterTask, InnerTask, InnerRes<:Object, OuterRes, Res](
    hint: String,
    outerTasks: IndexedSeq[OuterTask],
    prep: OuterTask => (IndexedSeq[InnerTask], InnerTask=>InnerRes, Array[InnerRes]=>OuterRes),
    innerCreate: Int=>Array[InnerRes],
    outerAggr: Seq[OuterRes]=>Res
  ): Future[Res] =
    execute[OuterTask, Future[OuterRes], Future[Res]](
      outerTasks,
      outerTask => {
        val (innerTasks, innerCalc, innerAggr) = prep(outerTask)
        execute[InnerTask,InnerRes,OuterRes](innerTasks, innerCalc, innerCreate, innerAggr)
      },
      new Array(_),
      s => seq(s)(shortEC).map(s=>outerAggr(s))(shortEC),
    ).flatten

  private def addHandler(transJoin: TransJoin, dir: Int, inputs: Seq[Index], res: List[KeyIterationHandler]): List[KeyIterationHandler] =
    if(inputs.forall(indexUtil.isEmpty)) res else transJoin.dirJoin(dir, inputs) :: res

  private def recalc(req: CalcReq, ec: ExecutionContext): Future[AggrDOut] = {
    //val at = System.nanoTime()
    val transJoin = joins(req.taskConf.joinPos).joins(req.inputDiffs)

    //if(transJoin.toString.contains("syncStatsJ")) ParallelExecutionCount.values(1).set(1)

    val handlers = addHandler(transJoin, -1, req.prevInputValues, addHandler(transJoin, +1, req.nextInputValues, Nil))

    //ParallelExecutionCount.values(1).set(0)

    val parallelPartCount = req.inputDiffs.map(rIndexUtil.subIndexOptimalCount).max
    val handlersByInvalidationSeq = handlers.groupBy(_.invalidateKeysFromIndexes).toSeq
    // so if invalidateKeysFromIndexes for handlers are same, then it's normal diff case, else `byEq` changes, and we need to recalc all
    type Task = (Seq[Array[RIndexKey]],Seq[KeyIterationHandler])
    val tasks: Seq[Task] = for {
      (invalidateKeysFromIndexes, handlers) <- handlersByInvalidationSeq
      partPos <- 0 until parallelPartCount
      subIndexes <- Seq(getSubIndexKeys(invalidateKeysFromIndexes, partPos, parallelPartCount)) if subIndexes.nonEmpty
    } yield (subIndexes, handlers)
    execute2[Task,KeyIterationHandler,AggrDOut,AggrDOut,AggrDOut](
      "C",
      tasks.toIndexedSeq,
      { case (invalidateKeysFromSubIndexes, handlers) =>
        val keys: Array[RIndexKey] = invalidateKeysFromSubIndexes match {
          case Seq(a) => a
          case as => as.toArray.flatten.distinct
        }
        (handlers.toIndexedSeq, handler => {
          val buffer = indexUtil.createBuffer()
          for (k <- keys) handler.handle(k, buffer)
          indexUtil.aggregate(buffer)
        }, indexUtil.aggregate)
      },
      new Array(_),
      r => {
        // println(s"calc ${(System.nanoTime-at)/1000000} ms ${req.inputDiffs.map(rIndexUtil.valueCount).sum} items ${tasks.size} of $parallelPartCount parts $transJoin")
        indexUtil.aggregate(r.toArray)
      },
    )
  }

  private def getSubIndexKeys(invalidateKeysFromIndexes: Seq[Index], partPos: Int, parallelPartCount: Int): Seq[Array[RIndexKey]] = for {
    index <- invalidateKeysFromIndexes
    subIndexKeys <- Seq(rIndexUtil.subIndexKeys(index, partPos, parallelPartCount)) if subIndexKeys.length > 0
  } yield subIndexKeys

  private class MutableSchedulingContext(
    val planner: MutablePlanner, val ec: ExecutionContext,
    val model: COWArr[WorldPos,Index], val inputDiffs: COWArr[InputPos,Index], val inputPrevValues: COWArr[InputPos,Index],
    val calculatedByBuildTask: COWArr[WorldPos,Array[Array[RIndexPair]]]
  )

  private def startBuild(context: MutableSchedulingContext, taskConf: BuildTaskConf): Future[Option[Ev]] = {
    val calculated = context.calculatedByBuildTask(taskConf.worldPos)
    if(calculated.length == 0) Future.successful(None) else {
      context.calculatedByBuildTask(taskConf.worldPos) = emptyCalculated
      val len = taskConf.outDiffPos.length
      val prev = new Array[Index](1 + len)
      prev(0) = context.model(taskConf.worldPos)
      var i = 0
      while(i < len){
        prev(i + 1) = context.inputDiffs(taskConf.outDiffPos(i))
        i += 1
      }
      continueBuild(taskConf, calculated, prev)(context.ec)
    }
  }

  private def continueBuild(
    taskConf: BuildTaskConf, calculated: Array[Array[RIndexPair]], prev: Array[Index]
  )(ec: ExecutionContext): Future[Option[Ev]] = Future{
    val prevDistinct = prev.distinct
    val task = indexUtil.buildIndex(prevDistinct, calculated)
    execute[IndexingSubTask,IndexingResult,Seq[Index]](task.subTasks.toIndexedSeq, rIndexUtil.execute, new Array(_), rIndexUtil.merge(task, _))
    .map{ nextDistinct =>
      prev.map(zip(ArraySeq.from(prevDistinct), nextDistinct).toMap)
    }(shortEC)
  }(ec).flatten.map(next=>Option(new BuiltEv(taskConf, prev, next)))(shortEC)
  private class BuiltEv(val taskConf: BuildTaskConf, val prev: Array[Index], val next: Array[Index]) extends Ev
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
      context.planner.setTodo(conf.subscribedInputPos(pos))
      i += 1
    }
  }

  private def startCalc(context: MutableSchedulingContext, taskConf: CalcTaskConf): Future[Option[Ev]] = {
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
    if(nonEmpty) continueCalc(new CalcReq(taskConf, prevInputs, inputs, diffs))(context.ec) else Future.successful(None)
  }
  private def continueCalc(req: CalcReq)(ec: ExecutionContext): Future[Option[Ev]] =
    Future(recalc(req, ec))(ec).flatten.map { aggr =>
      val diff = req.taskConf.outWorldPos.indices.toArray.map(i => req.taskConf.outWorldPos(i) -> indexUtil.byOutput(aggr, i))
        .filter(_._2.nonEmpty)
      Option(new CalculatedEv(diff))
    }(shortEC)
  private class CalculatedEv(val diff: Array[(WorldPos, Array[Array[RIndexPair]])]) extends Ev
  private def finishCalc(context: MutableSchedulingContext, ev: CalculatedEv): Unit = setTodoBuild(context, ev.diff)

  private def setTodoBuild(context: MutableSchedulingContext, diff: Array[(WorldPos, Array[Array[RIndexPair]])]): Unit = {
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
      context.planner.setTodo(conf.subscribedWorldPos(k))
      i += 1
    }
  }

  def replace(
    model: ReadModel, diff: Diffs, profiler: JoiningProfiling, executionContext: OuterExecutionContext
  ): ReadModel = {
    val planner = plannerFactory.createMutablePlanner(conf.plannerConf)
    val debuggingPlanner = planner //new DebuggingPlanner(planner, conf, joins)
    val modelImpl = model match{ case m: ReadModelImpl => m }
    val context = new MutableSchedulingContext(
      debuggingPlanner, executionContext.value,
      modelImpl.model.toMutable, modelImpl.inputDiffs.toMutable, modelImpl.inputPrevValues.toMutable,
      emptyCalculatedByBuildTask.toMutable
    )
    setTodoBuild(context, diff.map{ case (k: JoinKey, v) => (conf.worldPosFromKey(k),v) }.toArray)
    loop(context)
    new ReadModelImpl(context.model.toImmutable, context.inputDiffs.toImmutable, context.inputPrevValues.toImmutable, conf.worldPosFromKey)
  }
  private final class OuterEv(val exprPos: TaskPos, val event: Option[Ev])
  private def loop(context: MutableSchedulingContext): Unit = {
    val queue = new LinkedBlockingQueue[Try[OuterEv]]
    //val queue = new MpscBlockingConsumerArrayQueue[Try[OuterEv]](2048)
    val planner = context.planner
    //println(s"status counts: ${planner.planCount} ${planner.getStatusCounts}")
    while(planner.planCount > 0) {
      //ParallelExecutionCount.values(0).set(inProgress)
      //println(s"status counts: ${planner.planCount} ${planner.getStatusCounts}")
      while (planner.suggestedNonEmpty){
        val exprPos = planner.suggestedHead
        planner.setStarted(exprPos)
        (conf.tasks(exprPos) match {
          case taskConf: BuildTaskConf => startBuild(context, taskConf)
          case taskConf: CalcTaskConf => startCalc(context, taskConf)
        }).map(new OuterEv(exprPos,_))(shortEC).onComplete{ tEv => queue.put(tEv) }(shortEC)
      }
      val outerEv: OuterEv = queue.take().get
      outerEv.event match {
        case Some(ev: CalculatedEv) => finishCalc(context, ev)
        case Some(ev: BuiltEv) => finishBuild(context, ev)
        case None => ()
      }
      planner.setDone(outerEv.exprPos)
    }
  }
  def emptyReadModel: ReadModel = conf.emptyReadModel
}

class ReadModelImpl(
  val model: ImmArr[WorldPos,Index], val inputDiffs: ImmArr[InputPos,Index], val inputPrevValues: ImmArr[InputPos,Index],
  val worldPosFromKey: Map[JoinKey,WorldPos]
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

  def fill[K <: Int, V <: Object](value: V, createInnerArray: Int => Array[V], createOuterArray: Int => Array[Array[V]]): ImmArr[K, V] = {
    val inner = createInnerArray(innerSize)
    java.util.Arrays.setAll[V](inner, (_: Int) => value)
    val outer = createOuterArray(outerSize)
    java.util.Arrays.setAll[Array[V]](outer, (_: Int) => inner)
    new ImmArr(outer)
  }
}
final class ImmArr[K<:Int,V](data: Array[Array[V]]){
  def apply(pos: K): V = data(pos >> ImmArr.innerPower)(pos & ImmArr.innerMask)
  def toMutable: COWArr[K,V] = new COWArr(data)
}
final class COWArr[K<:Int,V](private var data: Array[Array[V]]){
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
    data(outerPos)(pos & innerMask) = value
  }
  def toImmutable: ImmArr[K,V] = {
    privateC = 0L
    new ImmArr(data)
  }
}

class DebuggingPlanner(inner: MutablePlanner, conf: SchedulerConf, joins: Seq[Join]) extends MutablePlanner {
  private def wrap(hint: String, exprPos: TaskPos, dummy: Unit): Unit =
    println(conf.tasks(exprPos) match {
      case t: CalcTaskConf =>
        val j = joins(t.joinPos)
        s"$hint #$exprPos calc ${j.assembleName} rule ${j.name}"
      case t: BuildTaskConf => s"$hint #$exprPos build ${/*t.key*/}"
    })
  override def setTodo(exprPos: TaskPos): Unit = wrap("setTodo   ",exprPos,inner.setTodo(exprPos))
  override def setDone(exprPos: TaskPos): Unit = wrap("setDone   ",exprPos,inner.setDone(exprPos))
  override def setStarted(exprPos: TaskPos): Unit = wrap("setStarted",exprPos,inner.setStarted(exprPos))
  override def suggestedNonEmpty: Boolean = inner.suggestedNonEmpty
  override def suggestedHead: TaskPos = inner.suggestedHead
  override def planCount: Int = inner.planCount
  override def getStatusCounts: Seq[Int] = inner.getStatusCounts
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