package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.{Tagged, TaskPos}
import ee.cone.c4di.{c4, c4multi}

import scala.collection.immutable.{BitSet, TreeSet}

class PlannerConfImpl(val taskUsers: Array[Array[TaskPos]]) extends PlannerConf

@c4("AssembleApp") final class PlannerFactoryImpl() extends PlannerFactory {
  def createConf(exprConfByPos: Seq[PlanTaskConf]): PlannerConf =
    new PlannerConfImpl(exprConfByPos.map(_.users.toArray).toArray)

  def createMutablePlanner(conf: PlannerConf): MutablePlanner =
    new MutablePlannerImpl(conf match { case c: PlannerConfImpl =>c.taskUsers })
}

@SuppressWarnings(Array("org.wartremover.warts.Var"))
final class MutablePlannerImpl(taskUsers: Array[Array[TaskPos]]) extends MutablePlanner {
  private sealed trait StatusTag
  private type Status = Byte with Tagged[StatusTag]

  private val reasonCountByExprPos: Array[Int] = new Array(taskUsers.length)
  private var reasonedExprCount = 0
  private val statusByExprPos: Array[Status] = new Array(taskUsers.length)
  private def mkSt(v: Int): Status = v.toByte.asInstanceOf[Status]
  private val noSt: Status = mkSt(0)
  private val todoSt: Status = mkSt(1)
  private val startedSt: Status = mkSt(2)
  private val suggestedByExprPos: Array[Boolean] = new Array(taskUsers.length)
  private var suggestedSet: TreeSet[TaskPos] = TreeSet.empty
  private val statusCounts = Array[Int](taskUsers.length, 0, 0)

  def setTodo(exprPos: TaskPos): Unit =
    if(statusByExprPos(exprPos) == noSt) setStatus(exprPos, noSt, todoSt, +1)

  def setDone(exprPos: TaskPos): Unit = {
    setStatus(exprPos, startedSt, todoSt, 0)
    setStatus(exprPos, todoSt, noSt, -1)
  }
  def setStarted(exprPos: TaskPos): Unit = setStatus(exprPos, todoSt, startedSt, 0)
  def suggested: Set[TaskPos] = suggestedSet
  def planCount: Int = reasonedExprCount
  def getStatusCounts: Seq[Int] = statusCounts.toSeq

  private def setStatus(exprPos: TaskPos, wasValue: Status, willValue: Status, countDir: Int): Unit = {
    assert(statusByExprPos(exprPos) == wasValue)
    statusByExprPos(exprPos) = willValue
    statusCounts(wasValue) -= 1
    statusCounts(willValue) += 1
    if(countDir != 0) changeCount(exprPos, countDir)
    updateSuggested(exprPos)
  }

  private def changeCount(exprPos: TaskPos, dir: Int): Unit = {
    val wasCount = reasonCountByExprPos(exprPos)
    val willCount = wasCount + dir
    assert(willCount >= 0)
    reasonCountByExprPos(exprPos) = willCount
    if (wasCount == 0 || willCount == 0) {
      reasonedExprCount += dir
      val users = taskUsers(exprPos)
      var uPos = 0
      while(uPos < users.length){
        changeCount(users(uPos), dir)
        uPos += 1
      }
    }
    updateSuggested(exprPos)
  }

  private def updateSuggested(exprPos: TaskPos): Unit = {
    val will = reasonCountByExprPos(exprPos) == 1 && statusByExprPos(exprPos) == todoSt
    if(suggestedByExprPos(exprPos) != will){
      suggestedByExprPos(exprPos) = will
      doUpdateSuggested(exprPos, will)
    }
  }
  private def doUpdateSuggested(exprPos: TaskPos, will: Boolean): Unit =
    suggestedSet = if(will) suggestedSet + exprPos else suggestedSet - exprPos
}

