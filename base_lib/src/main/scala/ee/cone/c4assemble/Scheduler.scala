package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.TaskPos
import ee.cone.c4assemble.RIndexTypes.{RIndexItem, RIndexKey}
import ee.cone.c4assemble.SchedulerConf._
import ee.cone.c4assemble.Types.{Index, emptyIndex}
import ee.cone.c4di.{c4, c4multi}

import java.lang.management.ManagementFactory
import java.util.concurrent.{BlockingQueue, ForkJoinPool, ForkJoinWorkerThread, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

object SchedulerConf {
  case class JoinInputPrevKey(exprPos: TaskPos, inputPos: Int) extends AssembledKey
  case class JoinInputDiffKey(exprPos: TaskPos, inputPos: Int) extends AssembledKey
  case class InputConf(was: Boolean, worldKey: JoinKey, prevKey: JoinInputPrevKey, diffKey: JoinInputDiffKey)
  sealed trait TaskConf {
    def exprPos: TaskPos
    def subscribeKeys: ArraySeq[AssembledKey]
    def planNotifyKeys: ArraySeq[AssembledKey]
  }
  case class CalcTaskConf(
    exprPos: TaskPos, subscribeKeys: ArraySeq[AssembledKey], planNotifyKeys: ArraySeq[AssembledKey],
    joinPos: Int, inputs: ArraySeq[InputConf], outKeys: ArraySeq[JoinKey]
  ) extends TaskConf
  case class BuildTaskConf(
    exprPos: TaskPos, subscribeKeys: ArraySeq[AssembledKey], planNotifyKeys: ArraySeq[AssembledKey],
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
    val calcTasks = ArraySeq.from(joins.zipWithIndex.map{ case (join,i) =>
      val exprPos = (worldKeys.length+i).asInstanceOf[TaskPos]
      val inputs = ArraySeq.from(join.inputWorldKeys.zipWithIndex.map { case (k:JoinKey,inputPos) =>
        InputConf(k.was, k.withWas(false), JoinInputPrevKey(exprPos, inputPos), JoinInputDiffKey(exprPos, inputPos))
      })
      val outputs = ArraySeq.from(join.outputWorldKeys.map{ case k: JoinKey => assert(!k.was); k })
      val subscribeKeys = inputs.map(_.diffKey)
      CalcTaskConf(exprPos, subscribeKeys,  outputs, i, inputs, outputs)
    })
    val calcInputsByWorldKey = calcTasks.flatMap(_.inputs).groupBy(_.worldKey).withDefaultValue(ArraySeq.empty)
    val buildTasks = worldKeys.zipWithIndex.map{ case (k,i) =>
      val exprPos = i.asInstanceOf[TaskPos]
      val outputs = calcInputsByWorldKey(k)
      val planNotifyKeys = outputs.filterNot(_.was).map(_.diffKey)
      BuildTaskConf(exprPos, ArraySeq(k), planNotifyKeys, k, outputs.map(_.diffKey))
    }
    val tasks = buildTasks ++ calcTasks
    val subscribed =
      tasks.flatMap(t => t.subscribeKeys.map(_->t.exprPos)).groupMap(_._1)(_._2).transform((_,v)=>Single(v))
    val planConf = plannerFactory.createConf(tasks.map(t => PlanTaskConf(t.planNotifyKeys.map(subscribed))))
    val conf = SchedulerConf(tasks, subscribed, planConf)
    //new Thread(new ThreadTracker).start()
    schedulerImplFactory.create(rulesByPriority, conf, joins)
  }
}

@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
@c4multi("AssembleApp") final class SchedulerImpl(
  val active: Seq[WorldPartRule], conf: SchedulerConf, joins: Seq[Join]
)(
  indexUtil: IndexUtil, readModelUtil: ReadModelUtil, rIndexUtil: RIndexUtil, plannerFactory: PlannerFactory,
) extends Replace {
  private trait Ev

  private def zip[A, B](a: ArraySeq[A], b: Seq[B]) = {
    assert(a.size == b.size)
    a.zip(b)
  }

  private class CalcReq(
    val taskConf: CalcTaskConf, val prevInputValues: Seq[Index], val nextInputValues: Seq[Index], val inputDiffs: Seq[Index]
  )

  private def execute[S, T, U](tasks: Seq[S], calc: S => T, aggr: Seq[T] => U, ec: ExecutionContext): Future[U] = {
    val (seqTasks, parTasks) = tasks.splitAt(1)
    val bsFO = parTasks.size match {
      case 0 => None
      case 1 => Option(Future(parTasks.map(calc))(ec))
      case _ => Option(seq(parTasks.map(b=>Future(calc(b))(ec)))(shortEC))
    }
    val asR = seqTasks.map(calc)
    bsFO.fold(Future.successful(aggr(asR)))(_.map(bsR=>aggr(asR ++ bsR))(shortEC))
  }

  private def execute2[OuterTask, InnerTask, InnerRes, OuterRes, Res](
    hint: String,
    outerTasks: Seq[OuterTask],
    prep: OuterTask => (Seq[InnerTask], InnerTask=>InnerRes, Seq[InnerRes]=>OuterRes),
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

  private val shortEC = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = ExecutionContext.defaultReporter(cause)
  }

  private def seq[T](tasks: Seq[Future[T]])(implicit ec: ExecutionContext) = Future.sequence(tasks)

  private def addHandler(transJoin: TransJoin, dir: Int, inputs: Seq[Index], res: List[KeyIterationHandler]): List[KeyIterationHandler] =
    if(inputs.forall(indexUtil.isEmpty)) res else transJoin.dirJoin(dir, inputs) :: res

  private def recalc(req: CalcReq, ec: ExecutionContext): Future[AggrDOut] = {
    val at = System.nanoTime()
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
      tasks,
      { case (invalidateKeysFromSubIndexes, handlers) =>
        val keys: Array[RIndexKey] = invalidateKeysFromSubIndexes match {
          case Seq(a) => a
          case as => as.toArray.flatten.distinct
        }
        (handlers, handler => {
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
    execute[IndexingSubTask,IndexingResult,Seq[Index]](task.subTasks, rIndexUtil.execute, rIndexUtil.merge(task, _), ec)
    .map{ nextDistinct =>
      prev.map(zip(ArraySeq.from(prevDistinct), nextDistinct).toMap)
    }(shortEC)
  }(ec).flatten.map(next=>Option(new BuiltEv(outKeys, prev, next.toArray)))(shortEC)
  private class BuiltEv(val keys: Array[AssembledKey], val prev: Array[Index], val next: Array[Index]) extends Ev
  private def finishBuild(context: MutableSchedulingContext, ev: BuiltEv): Unit = {
    val diff = for (i <- ev.keys.indices) yield {
      val k = ev.keys(i)
      assert(k.of(context.model) == ev.prev(i))
      k -> ev.next(i)
    }
    context.model = readModelUtil.updated(diff)(context.model)
    diff.collect{ case (k: JoinInputDiffKey, _) => k.exprPos }.foreach(context.planner.setTodo)
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
    val context = new MutableSchedulingContext(planner, executionContext.value, model, calculatedByBuildTask)
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
      while (planner.suggested.nonEmpty){
        val exprPos = planner.suggested.head
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