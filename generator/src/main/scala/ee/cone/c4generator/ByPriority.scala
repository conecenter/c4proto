package ee.cone.c4generator

case class PriorityState[K,V](map: Map[K,V], values: List[V], inProcess: Set[K], inProcessList: List[K])

class ByPriority[K,V](uses: K=>(List[K],List[V]=>V)) {
  private def add(state: PriorityState[K,V], key: K): PriorityState[K,V] =
    if(state.map.contains(key)) state else {
      if (state.inProcess(key)) throw new Exception(s"${state.inProcessList.mkString("\n")} \nhas $key")
      val (useKeys,toValue) = uses(key)
      val deeperState = state.copy(
        inProcess = state.inProcess + key,
        inProcessList = key :: state.inProcessList
      )
      val filled: PriorityState[K, V] = useKeys.foldLeft(deeperState)(add)
      val value = toValue(useKeys.map(filled.map))
      state.copy(map = filled.map + (key->value), values = value :: filled.values)
    }
  def apply(items: List[K]): List[V] =
    items.foldLeft(PriorityState[K,V](Map.empty[K,V],Nil,Set.empty[K],Nil))(add).values
}