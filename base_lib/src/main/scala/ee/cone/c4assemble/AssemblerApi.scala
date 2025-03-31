
package ee.cone.c4assemble

import ee.cone.c4assemble.PlannerTypes.TaskPos
import ee.cone.c4assemble.RIndexTypes.RIndexItem
import ee.cone.c4assemble.Types._

import scala.concurrent.{ExecutionContext, Future}

object Single {
  def apply[C](l: Seq[C]): C =
    if(l.sizeCompare(1)==0) l.head else throw defException(l)
  def apply[C](l: Seq[C], exception: Seq[C]=>Exception): C =
    if(l.sizeCompare(1)==0) l.head else throw exception(l)
  def option[C](l: Seq[C]): Option[C] = if(l.isEmpty) None else Option(apply(l))
  private def defException[C]: Seq[C]=>Exception = l => new Exception(
    if(l.isEmpty) "empty" else s"non-single: \n${l.head}, \n${l.tail.head} ..."
  )
}

object ToPrimaryKey {
  def apply(node: Product): String = RawToPrimaryKey.get(node)
} //s"1st field of ${node.getClass.getName} should be primary key"

trait WorldPartRule

class OriginalWorldPart[A<:Object](val outputWorldKeys: Seq[AssembledKey]) extends WorldPartRule with DataDependencyTo[A]

trait Replace {
  type Diffs = Seq[(AssembledKey, Array[Array[RIndexPair]])]
  def active: Seq[WorldPartRule]
  def replace(model: ReadModel, diff: Diffs, executionContext: ExecutionContext, profilingContext: RAssProfilingContext): ReadModel
  def emptyReadModel: ReadModel
  def report(model: ReadModel): Unit
}

trait RAssProfiling {
  def msWarnPeriod: Long
  def warn(content: String): Unit
  def debug(content: ()=>String): Unit
  def addPeriod(accId: Int, period: Long): Unit
}

case class RAssProfilingContext(accId: Int, eventIds: Seq[String], needDetailed: Boolean)

trait SchedulerFactory {
  def create(rulesByPriority: Seq[WorldPartRule]): Replace
}

trait TreeAssembler {
  def create(rules: List[WorldPartRule], isTarget: WorldPartRule=>Boolean): Replace
}

trait ByPriority {
  def byPriority[K,V](uses: K=>(List[K],List[V]=>V)): List[K] => List[V]
}

////
// moment -> mod/index -> key/srcId -> value -> count

trait AssembleDataDependencyFactory {
  def create(assembles: List[Assemble]): List[WorldPartRule]
}
