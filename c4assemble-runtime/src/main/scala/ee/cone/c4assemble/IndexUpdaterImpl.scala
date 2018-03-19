package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{Index, ReadModel}

import scala.collection.immutable.Map

class IndexUpdaterImpl() extends IndexUpdater {
  def setPart[K,V](worldKey: AssembledKey[Index[K,V]])(
    nextDiff: Map[K,Boolean], nextIndex: Index[K,V]
  ): WorldTransition⇒WorldTransition = transition ⇒ {
    def set[W](res: ReadModel, worldKey: AssembledKey[Index[K,V]], part: Map[K,W]) =
      (res + (worldKey → part)).asInstanceOf[Map[AssembledKey[_],Map[Object,W]]]
    val diff = set(transition.diff, worldKey, nextDiff)
    val next = set(transition.result, worldKey, nextIndex)
    WorldTransition(transition.prev, diff, next)
  }
  def diffOf[K,V](worldKey: AssembledKey[Index[K,V]]): WorldTransition⇒Map[K,Boolean] =
    _.diff.getOrElse(worldKey,Map.empty).asInstanceOf[Map[K,Boolean]]
}
