
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


case class ReverseInsertionOrder[K,V](map: Map[K,V]=Map.empty[K,V], values: List[V]=Nil) {
  def add(key: K, value: V): ReverseInsertionOrder[K,V] = {
    if(map.contains(key)) throw new Exception(s"has $key")
    ReverseInsertionOrder(map + (key→value), value :: values)
  }
}

/*
case class ReverseInsertionOrder[K,V](
  map: Map[K,V]=Map.empty[K,V], values: List[V]=Nil, inProcess: Set[K]=Set.empty[K]
)(
  fill: K⇒(List[K],List[V]⇒V)
) {
  def add(key: K): ReverseInsertionOrder[K,V] = if(map.contains(key)) this else {
    if(inProcess(key)) throw new Exception(s"$inProcess has $key")
    val reserved = ReverseInsertionOrder(map, values, inProcess + key)(fill)
    val(filled,value) = fill(reserved, key)
    ReverseInsertionOrder(filled.map + (key→value), value :: filled.values, inProcess)(fill)
  }
}
*/


////
// moment -> mod/index -> key/srcId -> value -> count


