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
  def setTodo(exprPos: TaskPos, value: Boolean): Unit
  def setStarted(exprPos: TaskPos, value: Boolean): Unit
  def suggestedNonEmpty: Boolean
  def suggestedHead: TaskPos
  def planCount: Int
  def getStarted: Seq[TaskPos]
  def reportStarted(): Unit
}
