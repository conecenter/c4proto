
package ee.cone.c4assemble

import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._

import scala.collection.immutable.{Seq,Map}

object Single {
  def apply[C](l: Seq[C]): C = if(l.tail.nonEmpty) throw new Exception else l.head
  def option[C](l: Seq[C]): Option[C] = if(l.isEmpty) None else Option(apply(l))
  def list[C](l: Iterable[C]): List[C] = if(l.isEmpty || l.tail.isEmpty) l.toList else throw new Exception
}

class OriginalWorldPart[A<:Object](val outputWorldKey: WorldKey[A]) extends DataDependencyTo[A]

object TreeAssemblerTypes {
  type Replace = Map[WorldKey[_],Index[Object,Object]] ⇒ World ⇒ World
  type MultiSet[T] = Map[T,Int]
}

trait TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace
}

////
// moment -> mod/index -> key/srcId -> value -> count


