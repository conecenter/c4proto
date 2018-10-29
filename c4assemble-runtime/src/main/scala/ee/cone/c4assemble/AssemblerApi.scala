
package ee.cone.c4assemble

import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._

import scala.collection.immutable.Seq

object Single {
  def apply[C](l: Seq[C]): C = if(l.isEmpty) {
    throw new Exception("empty")
  } else if(l.tail.isEmpty) l.head else {
    throw new Exception(s"non-single: \n${l.head}, \n${l.tail.head} ...")
  }
  def option[C](l: Seq[C]): Option[C] = if(l.isEmpty) None else Option(apply(l))
}

object ToPrimaryKey {
  def apply(node: Product): String =
    if(node.productArity > 0) node.productElement(0) match {
      case s: String ⇒ s
      case p: Product ⇒ ToPrimaryKey(p)
      case _ ⇒ throw new Exception(s"1st field of ${node.getClass.getName} should be primary key")
    } else ""
}

class OriginalWorldPart[A<:Object](val outputWorldKey: AssembledKey) extends DataDependencyTo[A]

object TreeAssemblerTypes {
  type Replace = (ReadModel, ReadModel, Boolean, SerialJoiningProfiling) ⇒ WorldTransition
}

trait TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace
}

trait ByPriority {
  def byPriority[K,V](uses: K⇒(List[K],List[V]⇒V)): List[K] ⇒ List[V]
}

////
// moment -> mod/index -> key/srcId -> value -> count

trait IndexUpdater {
  def setPart[K,V](worldKey: AssembledKey)(
    nextDiff: Index, nextIndex: Index
  ): WorldTransition⇒WorldTransition
}

trait AssembleSeqOptimizer {
  type Expr = WorldPartExpression with DataDependencyFrom[_] with DataDependencyTo[_]
  def optimize: List[Expr]⇒List[WorldPartExpression]
}

trait BackStageFactory {
  def create(l: List[DataDependencyFrom[_]]): List[WorldPartExpression]
}
