package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.TaskPos
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
  case class JoinInputPrevKey(joinPos: Int, inputPos: Int) extends AssembledKey
  case class JoinInputDiffKey(joinPos: Int, inputPos: Int) extends AssembledKey
  case class InputConf(was: Boolean, worldKey: JoinKey, prevKey: JoinInputPrevKey, diffKey: JoinInputDiffKey)
  sealed trait TaskConf {
    def subscribeKeys: ArraySeq[AssembledKey]
    def planNotifyKeys: ArraySeq[AssembledKey]
  }
  case class CalcTaskConf(
    subscribeKeys: ArraySeq[AssembledKey], planNotifyKeys: ArraySeq[AssembledKey],
    joinPos: Int, inputs: ArraySeq[InputConf], outKeys: ArraySeq[JoinKey]
  ) extends TaskConf
  case class BuildTaskConf(
    subscribeKeys: ArraySeq[AssembledKey], planNotifyKeys: ArraySeq[AssembledKey],
    key: JoinKey, outDiffKeys: ArraySeq[JoinInputDiffKey]
  ) extends TaskConf
}
case class SchedulerConf(tasks: ArraySeq[TaskConf], subscribed: Map[AssembledKey,TaskPos], plannerConf: PlannerConf)

@c4("AssembleApp") final class SchedulerFactoryImpl(
  expressionsDumpers: List[ExpressionsDumper[Unit]],
  schedulerImplFactory: SchedulerImplFactory, plannerFactory: PlannerFactory
) extends SchedulerFactory {
  def create(rulesByPriority: Seq[WorldPartRule]): Replace = {
    val rulesByPriorityArr = ArraySeq.from(rulesByPriority)
    val worldKeys = rulesByPriorityArr.collect{ case e: DataDependencyTo[_] => e.outputWorldKeys }.flatten
      .map{ case k: JoinKey => k }.distinct
    val joins = rulesByPriorityArr.collect { case e: Join => e }
    expressionsDumpers.foreach(_.dump(joins.toList))
    val calcTasks = ArraySeq.from(joins.zipWithIndex.map{ case (join,joinPos) =>
      val inputs = ArraySeq.from(join.inputWorldKeys.zipWithIndex.map { case (k:JoinKey,inputPos) =>
        InputConf(k.was, k.withWas(false), JoinInputPrevKey(joinPos, inputPos), JoinInputDiffKey(joinPos, inputPos))
      })
      val outputs = ArraySeq.from(join.outputWorldKeys.map{ case k: JoinKey => assert(!k.was); k })
      val subscribeKeys = inputs.map(_.diffKey)
      val planNotifyKeys = outputs
      CalcTaskConf(subscribeKeys, planNotifyKeys, joinPos, inputs, outputs)
    })
    val buildTasks = {
      // we need additional deps for planner for looped calculations: from was out build to other output builds, to prevent output-build start while looping
      val wasPairedKeys =
        joins.flatMap(_.inputWorldKeys.collect { case k: JoinKey if k.was => k.withWas(false) }).toSet
      val crossOutputDeps = (for {
        t <- calcTasks
        pairedKey <- t.outKeys if wasPairedKeys(pairedKey)
        k <- t.outKeys if k != pairedKey
      } yield pairedKey -> k).groupMap(_._1)(_._2).withDefaultValue(ArraySeq.empty)
      //println(s"crossOutputDeps: ${crossOutputDeps}")
      val calcInputsByWorldKey = calcTasks.flatMap(_.inputs).groupBy(_.worldKey).withDefaultValue(ArraySeq.empty)
      worldKeys.map{ k =>
        val outputs = calcInputsByWorldKey(k)
        val subscribeKeys = ArraySeq(k)
        val planNotifyKeys = outputs.filterNot(_.was).map(_.diffKey) ++ crossOutputDeps(k)
        BuildTaskConf(subscribeKeys, planNotifyKeys, k, outputs.map(_.diffKey))
      }
    }
    val tasks = buildTasks ++ calcTasks
    val subscribed = tasks.zipWithIndex
        .flatMap{ case (t,taskPos) => t.subscribeKeys.map(_->taskPos.asInstanceOf[TaskPos]) }
        .groupMapReduce(_._1)(_._2)((_,_)=>throw new Exception("conflicting subscriptions"))
    val planConf = plannerFactory.createConf(tasks.map(t => PlanTaskConf(t.planNotifyKeys.map(subscribed))))
    val conf = SchedulerConf(tasks, subscribed, planConf)
    //new Thread(new ThreadTracker).start()
    schedulerImplFactory.create(rulesByPriority, conf, joins)
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
  def execute[S, T<:Object, U](tasks: IndexedSeq[S], calc: S => T, aggr: Seq[T] => U, ec: ExecutionContext): Future[U]
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
  import ParallelExecution._
  private final class LTask[S,T](task: S, calc: S => T) extends RecursiveTask[T] {
    def compute(): T = calc(task)
  }

  def execute[S, T<:Object, U](tasks: IndexedSeq[S], calc: S => T, aggr: Seq[T] => U, ec: ExecutionContext): Future[U] = {
    val lTasks = new Array[LTask[S,T]](tasks.size)
    var i = lTasks.length
    while(i > 0){
      i -= 1
      val lTask  = new LTask(tasks(i), calc)
      lTasks(i) = lTask
      if(i > 0) lTask.fork() else lTask.invoke()
    }
    val resA = new Array[Object](lTasks.length)
    while(i < lTasks.length){
      resA(i) = lTasks(i).join()
      i += 1
    }
    Future.successful(aggr(ArraySeq.unsafeWrapArray(resA).asInstanceOf[ArraySeq[T]]))
  }
}

@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
@c4multi("AssembleApp") final class SchedulerImpl(
  val active: Seq[WorldPartRule], conf: SchedulerConf, joins: Seq[Join]
)(
  indexUtil: IndexUtil, readModelUtil: ReadModelUtil, rIndexUtil: RIndexUtil, plannerFactory: PlannerFactory,
  parallelExecution: ParallelExecution
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
    prep: OuterTask => (IndexedSeq[InnerTask], InnerTask=>InnerRes, Seq[InnerRes]=>OuterRes),
    outerAggr: Seq[OuterRes]=>Res,
    ec: ExecutionContext
  ): Future[Res] =
    execute[OuterTask, Future[OuterRes], Future[Res]](
      outerTasks,
      outerTask => {
        val (innerTasks, innerCalc, innerAggr) = prep(outerTask)
        execute[InnerTask,InnerRes,OuterRes](innerTasks, innerCalc, innerAggr, ec)
      },
      s => seq(s)(shortEC).map(outerAggr)(shortEC),
      ec
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
      r => {
        // println(s"calc ${(System.nanoTime-at)/1000000} ms ${req.inputDiffs.map(rIndexUtil.valueCount).sum} items ${tasks.size} of $parallelPartCount parts $transJoin")
        indexUtil.aggregate(r)
      },
      ec
    )
  }

  private def getSubIndexKeys(invalidateKeysFromIndexes: Seq[Index], partPos: Int, parallelPartCount: Int): Seq[Array[RIndexKey]] = for {
    index <- invalidateKeysFromIndexes
    subIndexKeys <- Seq(rIndexUtil.subIndexKeys(index, partPos, parallelPartCount)) if subIndexKeys.length > 0
  } yield subIndexKeys

  private class MutableSchedulingContext(
    val planner: MutablePlanner, val ec: ExecutionContext,
    var model: ReadModel, val calculatedByBuildTask: collection.mutable.Map[JoinKey,Array[Array[RIndexPair]]]
  )

  private def startBuild(context: MutableSchedulingContext, taskConf: BuildTaskConf): Future[Option[Ev]] =
    context.calculatedByBuildTask.remove(taskConf.key) match {
      case None => Future.successful(None)
      case Some(calculated) =>
        val outKeys = taskConf.outDiffKeys.toArray[AssembledKey].appended(taskConf.key)
        val prev = outKeys.map(_.of(context.model))
        continueBuild(calculated, outKeys, prev)(context.ec)
    }
  private def continueBuild(
    calculated: Array[Array[RIndexPair]], outKeys: Array[AssembledKey], prev: Array[Index]
  )(ec: ExecutionContext): Future[Option[Ev]] = Future{
    val prevDistinct = prev.distinct
    val task = indexUtil.buildIndex(prevDistinct, calculated)
    execute[IndexingSubTask,IndexingResult,Seq[Index]](task.subTasks.toIndexedSeq, rIndexUtil.execute, rIndexUtil.merge(task, _), ec)
    .map{ nextDistinct =>
      prev.map(zip(ArraySeq.from(prevDistinct), nextDistinct).toMap)
    }(shortEC)
  }(ec).flatten.map(next=>Option(new BuiltEv(outKeys, prev, next)))(shortEC)
  private class BuiltEv(val keys: Array[AssembledKey], val prev: Array[Index], val next: Array[Index]) extends Ev
  private def finishBuild(context: MutableSchedulingContext, ev: BuiltEv): Unit = {
    val diffA = new Array[(AssembledKey,Index)](ev.keys.length)
    for (i <- ev.keys.indices){
      val k = ev.keys(i)
      assert(k.of(context.model) == ev.prev(i))
      diffA(i) = k -> ev.next(i)
    }
    val diff = ArraySeq.unsafeWrapArray(diffA)
    context.model = readModelUtil.updated(diff)(context.model)
    diff.collect{ case (k: JoinInputDiffKey, _) => conf.subscribed(k) }.foreach(context.planner.setTodo)
  }

  private def startCalc(context: MutableSchedulingContext, taskConf: CalcTaskConf): Future[Option[Ev]] = {
    val diffs = taskConf.inputs.map(_.diffKey.of(context.model))
    if (diffs.forall(indexUtil.isEmpty)) Future.successful(None) else {
      val prevInputs = taskConf.inputs.map(_.prevKey.of(context.model))
      val inputs = taskConf.inputs.map(_.worldKey.of(context.model))
      val req = new CalcReq(taskConf, prevInputs, inputs, diffs)
      val replace = zip(taskConf.inputs, inputs).flatMap{ case (c, v) => Seq(c.prevKey -> v, c.diffKey -> emptyIndex) }
      context.model = readModelUtil.updated(replace)(context.model)
      continueCalc(req)(context.ec)
    }
  }
  private def continueCalc(req: CalcReq)(ec: ExecutionContext): Future[Option[Ev]] =
    Future(recalc(req, ec))(ec).flatten.map { aggr =>
      val diff = req.taskConf.outKeys.indices.toArray.map(i => req.taskConf.outKeys(i) -> indexUtil.byOutput(aggr, i))
        .filter(_._2.nonEmpty)
      Option(new CalculatedEv(diff))
    }(shortEC)
  private class CalculatedEv(val diff: Array[(JoinKey, Array[Array[RIndexPair]])]) extends Ev
  private def finishCalc(context: MutableSchedulingContext, ev: CalculatedEv): Unit = setTodoBuild(context, ev.diff)

  private def setTodoBuild(context: MutableSchedulingContext, diff: Array[(JoinKey, Array[Array[RIndexPair]])]): Unit = {
    val c = context.calculatedByBuildTask
    for ((k, v) <- diff) {
      c(k) = if (c.contains(k)) c(k) ++ v else v
      context.planner.setTodo(conf.subscribed(k))
    }
  }

  def replace(
    model: ReadModel, diff: Diffs, profiler: JoiningProfiling, executionContext: OuterExecutionContext
  ): ReadModel = {
    val planner = plannerFactory.createMutablePlanner(conf.plannerConf)
    val calculatedByBuildTask = new collection.mutable.HashMap[JoinKey, Array[Array[RIndexPair]]]
    val debuggingPlanner = planner //new DebuggingPlanner(planner, conf, joins)
    val context = new MutableSchedulingContext(debuggingPlanner, executionContext.value, model, calculatedByBuildTask)
    setTodoBuild(context, diff.map{ case (k: JoinKey, v) => (k,v) }.toArray)
    loop(context)
    context.model
  }
  private def loop(context: MutableSchedulingContext): Unit = {
    val queue = new LinkedBlockingQueue[Try[(TaskPos,Option[Ev])]]
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
        }).map((exprPos,_))(shortEC).onComplete{ tEv => queue.put(tEv) }(shortEC)
      }
      val (exprPos, event) = queue.take().get
      event.foreach{
        case ev: CalculatedEv => finishCalc(context, ev)
        case ev: BuiltEv => finishBuild(context, ev)
      }
      planner.setDone(exprPos)
    }
  }
}

class DebuggingPlanner(inner: MutablePlanner, conf: SchedulerConf, joins: Seq[Join]) extends MutablePlanner {
  private def wrap(hint: String, exprPos: TaskPos, dummy: Unit): Unit =
    println(conf.tasks(exprPos) match {
      case t: CalcTaskConf =>
        val j = joins(t.joinPos)
        s"$hint #$exprPos calc ${j.assembleName} rule ${j.name}"
      case t: BuildTaskConf => s"$hint #$exprPos build ${t.key}"
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