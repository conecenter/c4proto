package ee.cone.c4assemble

import ee.cone.c4proto.c4

import scala.collection.immutable.Map

@c4("AssembleApp") class ByPriorityImpl extends ByPriority {
  def byPriority[K,V](uses: K=>(List[K],List[V]=>V)): List[K] => List[V] =
    new ByPriorityBuilder[K,V](uses).apply
}

case class PriorityState[K,V](map: Map[K,V], values: List[V], inProcess: Set[K])

class ByPriorityBuilder[K,V](uses: K=>(List[K],List[V]=>V)) {
  private def add(state: PriorityState[K,V], key: K): PriorityState[K,V] =
    if(state.map.contains(key)) state else {
      if (state.inProcess(key)) throw new Exception(s"${state.inProcess.toList.mkString("\n")} \nhas $key")
      val (useKeys,toValue) = uses(key)
      val filled: PriorityState[K, V] = useKeys.foldLeft(state.copy(inProcess = state.inProcess + key))(add)
      val value = toValue(useKeys.map(filled.map))
      state.copy(map = filled.map + (key->value), values = value :: filled.values)
    }
  def apply(items: List[K]): List[V] =
    items.foldLeft(PriorityState[K,V](Map.empty[K,V],Nil,Set.empty[K]))(add).values
}

