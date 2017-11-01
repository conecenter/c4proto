package ee.cone.c4assemble

import ee.cone.c4assemble.HiddenC4Annotations.c4component

import scala.collection.immutable.Map

@c4component case class ByPriorityImpl() extends ByPriority {
  def byPriority[K,V](uses: K⇒(List[K],List[V]⇒V)): List[K] ⇒ List[V] =
    new ByPriorityBuilder[K,V](uses).apply
}

case class PriorityState[K,V](map: Map[K,V], values: List[V], inProcess: Set[K])

class ByPriorityBuilder[K,V](uses: K⇒(List[K],List[V]⇒V)) {
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

