package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{Index, ReadModel}

import scala.collection.immutable.Map

class IndexUpdaterImpl() extends IndexUpdater {
  def setPart[K,V](worldKey: AssembledKey)(
    nextDiff: Index, nextIndex: Index
  ): WorldTransition⇒WorldTransition = transition ⇒ {
    val diff = transition.diff + (worldKey → nextDiff)
    val next = transition.result + (worldKey → nextIndex)
    transition.copy(diff=diff,result=next)
  }
}
