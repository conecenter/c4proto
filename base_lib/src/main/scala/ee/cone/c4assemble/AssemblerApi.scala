
package ee.cone.c4assemble

import ee.cone.c4assemble.Types._

import scala.concurrent.Future

object Single {
  def apply[C](l: Seq[C]): C =
    if(l.nonEmpty && l.tail.isEmpty) l.head else throw defException(l)
  def apply[C](l: Seq[C], exception: Seq[C]=>Exception): C =
    if(l.nonEmpty && l.tail.isEmpty) l.head else throw exception(l)
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
  def active: List[WorldPartRule]
  def replace(
    prevWorld: ReadModel, diff: ReadModel, profiler: JoiningProfiling,
    executionContext: OuterExecutionContext
  ): Future[WorldTransition]
}

trait TreeAssembler {
  def create(rules: List[WorldPartRule], isTarget: WorldPartRule=>Boolean): Replace
}

trait ByPriority {
  def byPriority[K,V](uses: K=>(List[K],List[V]=>V)): List[K] => List[V]
}

////
// moment -> mod/index -> key/srcId -> value -> count

class IndexUpdates(val diffs: Seq[Index], val results: Seq[Index], val log: ProfilingLog)

trait IndexUpdater {
  def setPart(worldKeys: Seq[AssembledKey], update: Future[IndexUpdates], logTask: Boolean): WorldTransition=>WorldTransition
  //def setPart(worldKey: AssembledKey, update: Future[IndexUpdate], logTask: Boolean): WorldTransition=>WorldTransition
}

trait AssembleSeqOptimizer {
  type Expr = WorldPartExpression with DataDependencyFrom[_] with DataDependencyTo[_]
  def optimize: List[Expr]=>List[WorldPartExpression]
}

trait BackStageFactory {
  def create(l: List[DataDependencyFrom[_]]): List[WorldPartExpression]
}

trait AssembleDataDependencyFactory {
  def create(assembles: List[Assemble]): List[WorldPartRule]
}
