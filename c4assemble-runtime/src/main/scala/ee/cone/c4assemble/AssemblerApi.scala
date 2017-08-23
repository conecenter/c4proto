
package ee.cone.c4assemble

import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._

import scala.collection.immutable.{Seq,Map}

object Single {
  def apply[C](l: Seq[C]): C = if(l.tail.nonEmpty) throw new Exception else l.head
  def option[C](l: Seq[C]): Option[C] = if(l.isEmpty) None else Option(apply(l))
  def list[C](l: Iterable[C]): List[C] = if(l.isEmpty || l.tail.isEmpty) l.toList else throw new Exception
}

object ToPrimaryKey {
  def apply(node: Product): String = node.productElement(0) match {
    case s: String ⇒ s
    case _ ⇒ throw new Exception(s"1st field of ${node.getClass.getName} should be primary key")
  }
}

class OriginalWorldPart[A<:Object](val outputWorldKey: AssembledKey[A]) extends DataDependencyTo[A]

object TreeAssemblerTypes {
  type Replace = Map[AssembledKey[_],Index[Object,Object]] ⇒ ReadModel ⇒ ReadModel
  type MultiSet[T] = Map[T,Int]
}

trait TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace
}

trait ByPriority {
  def byPriority[K,V](uses: K⇒(List[K],List[V]⇒V)): List[K] ⇒ List[V]
}

////
// moment -> mod/index -> key/srcId -> value -> count


