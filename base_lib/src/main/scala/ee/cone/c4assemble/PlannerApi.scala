package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.TaskPos

case class PlanTaskConf(users: Seq[TaskPos])

object PlannerTypes {
  type Tagged[U] = { type Tag = U }
  sealed trait TaskPosTag
  type TaskPos = Int with Tagged[TaskPosTag]
}

trait PlannerConf
trait PlannerFactory {
  def createConf(exprConfByPos: Seq[PlanTaskConf]): PlannerConf
  def createMutablePlanner(conf: PlannerConf): MutablePlanner
}

trait MutablePlanner {
  def setTodo(exprPos: TaskPos): Unit
  def setDone(exprPos: TaskPos): Unit
  def setStarted(exprPos: TaskPos): Unit
  def suggested: Set[TaskPos]
  def planCount: Int
  def getStatusCounts: Seq[Int]
}
