package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.TaskPos
import ee.cone.c4assemble.RIndexTypes.{RIndexItem, RIndexKey}
import ee.cone.c4assemble.SchedulerConf._
import ee.cone.c4assemble.Types.{Index, emptyIndex}
import ee.cone.c4di.{c4, c4multi}

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

object SchedulerConf {
  case class JoinInputPrevKey(exprPos: TaskPos, inputPos: Int) extends AssembledKey
  case class JoinInputDiffKey(exprPos: TaskPos, inputPos: Int) extends AssembledKey
  case class InputConf(was: Boolean, worldKey: JoinKey, prevKey: JoinInputPrevKey, diffKey: JoinInputDiffKey)
  case class TaskConf(exprPos: TaskPos, inputs: ArraySeq[InputConf], outKeys: ArraySeq[JoinKey])
  case class IndexUserConf(diffKey: JoinInputDiffKey, exprPos: TaskPos)
}
case class SchedulerConf(indexUsers: Map[JoinKey,ArraySeq[IndexUserConf]], tasks: ArraySeq[TaskConf], plannerConf: PlannerConf)

@c4("AssembleApp") final class SchedulerFactoryImpl(
  expressionsDumpers: List[ExpressionsDumper[Unit]],
  schedulerImplFactory: SchedulerImplFactory, plannerFactory: PlannerFactory
) extends SchedulerFactory {
  def create(rulesByPriority: Seq[WorldPartRule]): Replace = {
    val joins = rulesByPriority.collect { case e: Join => e }
    expressionsDumpers.foreach(_.dump(joins.toList))
    val tasks = ArraySeq.from(joins.zipWithIndex.map{ case (join,i) =>
      val exprPos = i.asInstanceOf[TaskPos]
      val inputs = ArraySeq.from(join.inputWorldKeys.zipWithIndex.map { case (k:JoinKey,inputPos) =>
        InputConf(k.was, k.withWas(false), JoinInputPrevKey(exprPos, inputPos), JoinInputDiffKey(exprPos, inputPos))
      })
      val outputs = ArraySeq.from(join.outputWorldKeys.map{ case k: JoinKey => assert(!k.was); k })
      TaskConf(exprPos: TaskPos, inputs, outputs)
    })
    val indexUsers = (for(task<-tasks; inp<-task.inputs) yield inp.worldKey -> IndexUserConf(inp.diffKey,task.exprPos))
      .groupMap(_._1)(_._2).withDefaultValue(ArraySeq.empty)
    val planIndexUsers = (for(task<-tasks; inp<-task.inputs if !inp.was) yield inp.worldKey -> task.exprPos)
      .groupMap(_._1)(_._2).withDefaultValue(ArraySeq.empty)
    val planTasks: Seq[PlanTaskConf] = tasks.map(task => PlanTaskConf(task.outKeys.flatMap(planIndexUsers)))
    val conf = SchedulerConf(indexUsers, tasks, plannerFactory.createConf(planTasks))
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
  private class RecalculatedEv(val done: Option[TaskPos], val diff: ArraySeq[(AssembledKey, Array[RIndexPair])]) extends Ev
  private class BuiltEv(val done: Option[TaskPos], val diff: ArraySeq[(JoinKey, Index)]) extends Ev

  private def applyPatch(diffs: ArraySeq[(JoinKey, Index)])(implicit ec: ExecutionContext): ReadModel=>ReadModel = model => {
    val extendedPairs = diffs.flatMap{ case (k,v) =>
      conf.indexUsers(k).map(_.diffKey).appended(k).map(ik=>ik->(ik.of(model),v))
    }
    val merged = Await.result(mergeIndexP(extendedPairs.map(_._2)), Duration.Inf)
    readModelUtil.updated(zip(extendedPairs.map(_._1), merged))(model)
  }

  private def mergeIndexP(tasks: Seq[(Index, Index)])(implicit ec: ExecutionContext): Future[Seq[Index]] = {
    val (simplePairs, complexPairs) = tasks.distinct.partition { case (a, b) => indexUtil.isEmpty(a) || indexUtil.isEmpty(b) }
    execute2[(Index, Index), IndexingSubTask, IndexingResult, ((Index, Index), Index), Seq[Index]](
      "M",
      complexPairs,
      { case (a, b) =>
        val task = indexUtil.mergeIndex(a, b)
        (task.subTasks, rIndexUtil.execute, l => (a, b) -> rIndexUtil.merge(task, l))
      },
      res => tasks.map((simplePairs.map{ case (a, b) => (a, b) -> (if(indexUtil.isEmpty(b)) a else b) } ++ res).toMap),
      ec
    )
  }

  private def buildIndexP(diff: ArraySeq[Array[RIndexPair]])(implicit ec: ExecutionContext): Future[Seq[Index]] =
    execute2[Array[RIndexPair], IndexingSubTask, IndexingResult, Index, Seq[Index]](
      "B",
      diff,
      pairs => {
        val task = indexUtil.buildIndex(pairs)
        (task.subTasks, rIndexUtil.execute, rIndexUtil.merge(task, _))
      },
      identity,
      ec
    )

  def zip[A, B](a: ArraySeq[A], b: Seq[B]) = {
    assert(a.size == b.size)
    a.zip(b)
  }

  private class CalcReq(
    val taskConf: TaskConf, val prevInputValues: Seq[Index], val nextInputValues: Seq[Index], val inputDiffs: Seq[Index]
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
/*
        ParallelExecutionCount.add((hint match {
          case "B" => 0
          case "M" => 1
          case "C" => 2
        }) * 5 + (innerTasks.size match {
          case 0 => 0
          case 1 => 1
          case 2 => 2
          case a if a < 15 => 3
          case a if a < 40 => 4
        }), 1)*/

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
    val transJoin = joins(req.taskConf.exprPos).joins(req.inputDiffs)
    val handlers = addHandler(transJoin, -1, req.prevInputValues, addHandler(transJoin, +1, req.nextInputValues, Nil))
    val parallelPartCount = 32
    val handlersByInvalidationSeq = handlers.groupBy(_.invalidateKeysFromIndexes).toSeq
    // so if invalidateKeysFromIndexes for handlers are same, then it's normal diff case, else `byEq` changes, and we need to recalc all
    type Task = (Seq[Array[RIndexKey]],Seq[KeyIterationHandler])
    val tasks: Seq[Task] = for {
      (invalidateKeysFromIndexes, handlers) <- handlersByInvalidationSeq
      partPos <- 0 until parallelPartCount
      subIndexes <- Seq(getSubIndexKeys(invalidateKeysFromIndexes, partPos, parallelPartCount)) if subIndexes.nonEmpty
    } yield (subIndexes, handlers)
/*
    ParallelExecutionCount.add(tasks.size match {
      case 0 => 0
      case 1 => 1
      case 2 => 2
      case a if a < 15 => 3
      case a if a < 30 => 4
      case a if a < 40 => 5
    }, 1)*/

    //val outRange = (0 until Single(handlers.map(_.outCount).distinct))
    execute2[Task,KeyIterationHandler,AggrDOut,Seq[AggrDOut],AggrDOut](
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
        }, identity)
      },
      r => indexUtil.aggregate(r.flatten),
      ec
    )
  }

  private def getSubIndexKeys(invalidateKeysFromIndexes: Seq[Index], partPos: Int, parallelPartCount: Int): Seq[Array[RIndexKey]] = for {
    index <- invalidateKeysFromIndexes
    subIndexKeys <- Seq(rIndexUtil.subIndexKeys(index, partPos, parallelPartCount)) if subIndexKeys.length > 0
  } yield subIndexKeys

  private class MutableSchedulingContext(
    val queue: BlockingQueue[Try[Ev]], val planner: MutablePlanner, val ec: ExecutionContext, var inProgress: Int,
    var model: ReadModel,
  )

  def replace(
    model: ReadModel, diff: Diffs, profiler: JoiningProfiling, executionContext: OuterExecutionContext
  ): ReadModel = {
    val planner = plannerFactory.createMutablePlanner(conf.plannerConf)
    val queue = new LinkedBlockingQueue[Try[Ev]]
    val context = new MutableSchedulingContext(queue, planner, executionContext.value, 0, model)
    sendNow(context, new RecalculatedEv(None,ArraySeq.from(diff)))
    iteration(context)
  }
  @tailrec private def iteration(context: MutableSchedulingContext): ReadModel = {
    import context._
    ParallelExecutionCount.values(0).set(inProgress)
    assert(inProgress >= 0)
    if(planner.planCount <= 0 && inProgress <= 0 && queue.isEmpty) model else {
      if(planner.suggested.nonEmpty){
        val exprPos = planner.suggested.head
        planner.setStarted(exprPos)
        val taskConf: TaskConf = conf.tasks(exprPos)
        val diffs = taskConf.inputs.map(_.diffKey.of(model))
        if (diffs.forall(indexUtil.isEmpty)){
          sendNow(context, new RecalculatedEv(Option(exprPos), ArraySeq.empty))
        } else {
          val prevInputs = taskConf.inputs.map(_.prevKey.of(model))
          val inputs = taskConf.inputs.map(_.worldKey.of(model))
          val req = new CalcReq(taskConf, prevInputs, inputs, diffs)
          val repl = zip(taskConf.inputs, inputs).flatMap { case (c, v) => Seq(c.prevKey -> v, c.diffKey -> emptyIndex) }
          model = readModelUtil.updated(repl)(model)
          send(context, ()=>{
            recalc(req, ec).map{ aggr =>
              val diff = taskConf.outKeys.zipWithIndex.flatMap{ case (k,pos) =>
                val items = indexUtil.byOutput(aggr, pos)
                if(items.isEmpty) ArraySeq.empty else ArraySeq(k -> items)
              }
              new RecalculatedEv(Option(exprPos), diff)
            }(shortEC)
          })
        }
      } else {
        val event = queue.take().get
        context.inProgress -= 1
        event match {
          case ev: RecalculatedEv =>
            send(context, ()=>{
              buildIndexP(ev.diff.map(_._2))(ec).map{ diff =>
                val fDiff: ArraySeq[(JoinKey, Index)] = zip(ev.diff.map(_._1), diff)
                  .collect { case (k: JoinKey, v) if !indexUtil.isEmpty(v) => (k, v) }
                new BuiltEv(ev.done, fDiff)
              }(ec)
            })
          case ev: BuiltEv =>
            model = applyPatch(ev.diff)(ec)(model)
            //for(exprPos <- ev.done) println(s"SCH finishing $exprPos")
            planner.setDoneTodo(ev.done, (for ((k, _) <- ev.diff; u <- conf.indexUsers(k)) yield u.exprPos).toSet)
            //ParallelExecutionCount.add(1, 1)
        }
      }
      iteration(context)
    }
  }

  private def sendNow(context: MutableSchedulingContext, ev: Ev): Unit = {
    context.inProgress += 1
    context.queue.put(Success(ev))
  }
  def send(context: MutableSchedulingContext, calc: ()=>Future[Ev]): Unit = {
    context.inProgress += 1
    Future(calc())(context.ec).flatten.onComplete{ tEv =>
      context.queue.put(tEv)
    }(shortEC)
  }
}

/*


plan:
chk wrld hash
conc-y vs par-m -- is cpu busy
All? par Each
fjp

ideas:
outKey as task
we need diff anyway for next recalc, but do we need to merge-w/o-build 
    or just (prev,pairs)->build_merge->(diff,next)
bucketCount for index dynamic on 1st join
if value-count >> key-count (and it's not Values?), then segmented bucketPos based on both

*/