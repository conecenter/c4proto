package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.TaskPos
import ee.cone.c4assemble.SchedulerConf._
import ee.cone.c4assemble.Types.{Index, emptyIndex}
import ee.cone.c4di.{c4, c4multi}

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

object SchedulerConf {
  case class JoinInputPrevKey(exprPos: TaskPos, inputPos: Int) extends AssembledKey
  case class JoinInputDiffKey(exprPos: TaskPos, inputPos: Int) extends AssembledKey
  case class InputConf(was: Boolean, worldKey: JoinKey, prevKey: JoinInputPrevKey, diffKey: JoinInputDiffKey)
  case class TaskConf(exprPos: TaskPos, inputs: IndexedSeq[InputConf], outKeys: IndexedSeq[JoinKey])
  case class IndexUserConf(diffKey: JoinInputDiffKey, exprPos: TaskPos)
}
case class SchedulerConf(indexUsers: Map[JoinKey,IndexedSeq[IndexUserConf]], tasks: IndexedSeq[TaskConf], plannerConf: PlannerConf)

@c4("AssembleApp") final class SchedulerFactoryImpl(
  expressionsDumpers: List[ExpressionsDumper[Unit]],
  schedulerImplFactory: SchedulerImplFactory, plannerFactory: PlannerFactory
) extends SchedulerFactory {
  def create(rulesByPriority: Seq[WorldPartRule]): Replace = {
    val joins = rulesByPriority.collect { case e: Join => e }
    expressionsDumpers.foreach(_.dump(joins.toList))
    val tasks = joins.zipWithIndex.map{ case (join,i) =>
      val exprPos = i.asInstanceOf[TaskPos]
      val inputs = join.inputWorldKeys.zipWithIndex.map { case (k:JoinKey,inputPos) =>
        InputConf(k.was, k.withWas(false), JoinInputPrevKey(exprPos, inputPos), JoinInputDiffKey(exprPos, inputPos))
      }.toIndexedSeq
      val outputs = join.outputWorldKeys.map{ case k: JoinKey => assert(!k.was); k }.toIndexedSeq
      TaskConf(exprPos: TaskPos, inputs, outputs)
    }.toIndexedSeq
    val indexUsers = (for(task<-tasks; inp<-task.inputs) yield inp.worldKey -> IndexUserConf(inp.diffKey,task.exprPos))
      .groupMap(_._1)(_._2).withDefaultValue(IndexedSeq.empty)
    val planIndexUsers = (for(task<-tasks; inp<-task.inputs if !inp.was) yield inp.worldKey -> task.exprPos)
      .groupMap(_._1)(_._2).withDefaultValue(IndexedSeq.empty)
    val planTasks: Seq[PlanTaskConf] = tasks.map(task => PlanTaskConf(task.outKeys.flatMap(planIndexUsers)))
    val conf = SchedulerConf(indexUsers, tasks, plannerFactory.createConf(planTasks))
    schedulerImplFactory.create(rulesByPriority, conf, joins)
  }
}

@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
@c4multi("AssembleApp") final class SchedulerImpl(
  val active: Seq[WorldPartRule], conf: SchedulerConf, joins: Seq[Join]
)(
  indexUtil: IndexUtil, readModelUtil: ReadModelUtil, plannerFactory: PlannerFactory,
) extends Replace {
  class PatchEv(val done: Option[TaskPos], val diff: Diffs)

  def applyPatch(diffs: Seq[(JoinKey, Index)])(implicit ec: ExecutionContext): ReadModel=>ReadModel = model => {
    val extendedPairs = diffs.flatMap{ case (k,v) => (Seq(k)++conf.indexUsers(k).map(_.diffKey)).map(ik=>ik->(ik.of(model),v)) }
    val merged = indexUtil.mergeIndexP(extendedPairs.map(_._2))
    readModelUtil.updated(zip(extendedPairs.map(_._1), merged))(model)
  }
  def zip[A, B](a: Seq[A], b: Seq[B]) = {
    assert(a.size == b.size)
    a.zip(b)
  }

  private class CalcReq(
    val taskConf: TaskConf, val prevInputValues: Seq[Index], val nextInputValues: Seq[Index], val inputDiffs: Seq[Index]
  )
  private def recalc(req: CalcReq, oEc: OuterExecutionContext): Future[Seq[Index]] = Future{
    val join = joins(req.taskConf.exprPos)
    val runJoin = join.joins(req.inputDiffs, oEc)
    val prevJoinRes = runJoin.dirJoin(-1, req.prevInputValues)
    val nextJoinRes = runJoin.dirJoin(+1, req.nextInputValues)
    implicit val ec: ExecutionContext = parasitic
    for {
      joinRes: Seq[AggrDOut] <- Future.sequence(prevJoinRes ++ nextJoinRes)
      indexDiffs: Seq[Index] <- Future.sequence(indexUtil.buildIndex(joinRes)(oEc.value))
    } yield indexDiffs
  }(oEc.value).flatten

  def replace(
    model: ReadModel, diff: Diffs, profiler: JoiningProfiling, executionContext: OuterExecutionContext
  ): ReadModel = {
    val planner = plannerFactory.createMutablePlanner(conf.plannerConf)
    val queue = new LinkedBlockingQueue[Try[PatchEv]]
    queue.put(Success(new PatchEv(None,diff)))
    iteration(queue, planner, executionContext, model)
  }
  @tailrec private def iteration(
    queue: BlockingQueue[Try[PatchEv]], planner: MutablePlanner, ec: OuterExecutionContext, wasModel: ReadModel
  ): ReadModel =
    if(planner.suggested.nonEmpty){
      val exprPos = planner.suggested.head
      planner.setStarted(exprPos)
      val taskConf: TaskConf = conf.tasks(exprPos)
      //println(s"SCH starting $exprPos ${joins(exprPos).name}")
      val diffs = taskConf.inputs.map(_.diffKey.of(wasModel))
      if (diffs.forall(indexUtil.isEmpty)){
        ParallelExecutionCount.add(3,1)
        queue.put(Success(new PatchEv(Option(exprPos), Nil)))
        iteration(queue, planner, ec, wasModel)
      } else {
        val prevInputs = taskConf.inputs.map(_.prevKey.of(wasModel))
        val inputs = taskConf.inputs.map(_.worldKey.of(wasModel))
        val req = new CalcReq(taskConf, prevInputs, inputs, diffs)
        val repl = zip(taskConf.inputs, inputs).flatMap { case (c, v) => Seq(c.prevKey -> v, c.diffKey -> emptyIndex) }
        val model = readModelUtil.updated(repl)(wasModel)
        recalc(req, ec).map(diffs=>new PatchEv(Option(exprPos), zip(taskConf.outKeys, diffs)))(parasitic)
          .onComplete(queue.put)(parasitic)
        ParallelExecutionCount.add(2,1)
        iteration(queue, planner, ec, model)
      }
    } else if(planner.planCount > 0 || !queue.isEmpty){
      val ev = queue.take().get
      val fDiff = ev.diff.collect { case (k: JoinKey, v) if !indexUtil.isEmpty(v) => (k, v) }
      val model = applyPatch(fDiff)(ec.value)(wasModel)
      //for(exprPos <- ev.done) println(s"SCH finishing $exprPos")
      planner.setDoneTodo(ev.done, (for ((k, _) <- fDiff; u <- conf.indexUsers(k)) yield u.exprPos).toSet)
      ParallelExecutionCount.add(1, 1)
      iteration(queue, planner, ec, model)
    } else {
      //println(s"SCH Q ${queue.size} statuses "+planner.getStatusCounts.mkString(" "))
      wasModel
    }
}
