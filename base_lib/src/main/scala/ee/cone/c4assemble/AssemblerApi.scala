
package ee.cone.c4assemble

import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._

import scala.collection.immutable.Seq
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
  def apply(node: Product): String =
    if(node.productArity > 0) node.productElement(0) match {
      case s: String => s
      case p: Product => ToPrimaryKey(p)
      case _ => throw new Exception(s"1st field of ${node.getClass.getName} should be primary key")
    } else ""
}

class OriginalWorldPart[A<:Object](val outputWorldKey: AssembledKey) extends DataDependencyTo[A]

object TreeAssemblerTypes {
  type Replace = (ReadModel, ReadModel, JoiningProfiling, OuterExecutionContext) => Future[WorldTransition]
}

trait TreeAssembler {
  def replace: List[DataDependencyTo[_]] => Replace
}

trait ByPriority {
  def byPriority[K,V](uses: K=>(List[K],List[V]=>V)): List[K] => List[V]
}

////
// moment -> mod/index -> key/srcId -> value -> count

class IndexUpdate(val diff: Index, val result: Index, val log: ProfilingLog)

trait IndexUpdater {
  def setPart(worldKey: AssembledKey, update: Future[IndexUpdate], logTask: Boolean): WorldTransition=>WorldTransition
}

trait AssembleSeqOptimizer {
  type Expr = WorldPartExpression with DataDependencyFrom[_] with DataDependencyTo[_]
  def optimize: List[Expr]=>List[WorldPartExpression]
}

trait BackStageFactory {
  def create(l: List[DataDependencyFrom[_]]): List[WorldPartExpression]
}
