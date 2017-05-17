
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

/*
case class ReverseInsertionOrder[K,V](map: Map[K,V]=Map.empty[K,V], values: List[V]=Nil) {
  def add(key: K, value: V): ReverseInsertionOrder[K,V] = {
    if(map.contains(key)) throw new Exception(s"has $key")
    ReverseInsertionOrder(map + (key→value), value :: values)
  }
}
*/


//remember reverse
case class PriorityState[K,V](map: Map[K,V], values: List[V], inProcess: Set[K])
case class ByPriority[K,V](uses: K⇒(List[K],List[V]⇒V)) {
  private def add(state: PriorityState[K,V], key: K): PriorityState[K,V] =
    if(state.map.contains(key)) state else {
      if(state.inProcess(key)) throw new Exception(s"${state.inProcess} has $key")
      val(useKeys,toValue) = uses(key)
      val filled = (state.copy(inProcess = state.inProcess + key) /: useKeys)(add)
      val value = toValue(useKeys.map(filled.map))
      state.copy(map = filled.map + (key→value), values = value :: filled.values)
    }
  def apply(items: List[K]): List[V] =
    (PriorityState[K,V](Map.empty[K,V],Nil,Set.empty[K]) /: items)(add).values
}




////
// moment -> mod/index -> key/srcId -> value -> count


