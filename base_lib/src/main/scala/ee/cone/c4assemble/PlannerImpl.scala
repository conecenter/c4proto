package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.{Tagged, TaskPos}
import ee.cone.c4di.c4

class PlannerConfImpl(val taskUsers: Array[Array[TaskPos]]) extends PlannerConf

@c4("AssembleApp") final class PlannerFactoryImpl() extends PlannerFactory {
  def createConf(exprConfByPos: Seq[PlanTaskConf]): PlannerConf =
    new PlannerConfImpl(exprConfByPos.map(_.users.toArray).toArray)

  def createMutablePlanner(conf: PlannerConf): MutablePlanner =
    new MutablePlannerImpl(conf match { case c: PlannerConfImpl =>c.taskUsers })
}

@SuppressWarnings(Array("org.wartremover.warts.Var"))
final class MutablePlannerImpl(taskUsers: Array[Array[TaskPos]]) extends MutablePlanner {
  private val reasonCountByExprPos: Array[Int] = new Array(taskUsers.length)
  private var reasonedExprCount = 0
  private val todoSet = new RBitSet[TaskPos](taskUsers.length, (exprPos,d)=>{ changeCount(exprPos, d) })
  private val startedSet = new RBitSet[TaskPos](taskUsers.length, (exprPos,d)=>{ changeCount(exprPos, d) })
  private val suggestedSet = new RBitSet[TaskPos](taskUsers.length, (_,d)=>{ suggestedSize += d })
  private var suggestedSize = 0

  def setTodo(exprPos: TaskPos, value: Boolean): Unit = { todoSet(exprPos) = value }

  def setStarted(exprPos: TaskPos, value: Boolean): Unit = { startedSet(exprPos) = value }

  def suggestedNonEmpty: Boolean = suggestedSize > 0

  def suggestedHead: TaskPos = suggestedSet.head.asInstanceOf[TaskPos]

  def planCount: Int = reasonedExprCount

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
    suggestedSet(exprPos) = reasonCountByExprPos(exprPos) == 1 && todoSet(exprPos) && !startedSet(exprPos)
  }

  def getStarted: Seq[TaskPos] = (taskUsers.indices.asInstanceOf[Seq[TaskPos]]).filter(startedSet(_))
  def reportStarted(): Unit = ()
}

trait RBitSetChangeHandler[K<:Int]{
  def handle(pos: K, dir: Int): Unit
}
final class RBitSet[K<:Int](maxSize: Int, changeHandler: RBitSetChangeHandler[K]){
  private val LogWL = 6
  private val WordLength = 64
  private val elems = new Array[Long](((maxSize-1) >> LogWL)+1)

  def apply(elem: K): Boolean = {
    require(elem >= 0)
    val idx = elem >> LogWL
    val wasW = elems(idx)
    val shifted = 1L << elem
    val contains = (wasW & shifted) != 0L
    //
    contains
  }

  def update(elem: K, value: Boolean): Unit = {
    require(elem >= 0)
    val idx = elem >> LogWL
    val wasW = elems(idx)
    val shifted = 1L << elem
    val contains = (wasW & shifted) != 0L
    //
    if(value && !contains){
      elems(idx) = wasW | shifted
      changeHandler.handle(elem, +1)
    } else if(!value && contains){
      elems(idx) = wasW & ~shifted
      changeHandler.handle(elem, -1)
    }
  }

  def head: Int = {
    var i = 0
    while (elems(i) == 0) i += 1
    java.lang.Long.numberOfTrailingZeros(elems(i)) + (i * WordLength)
  }
}