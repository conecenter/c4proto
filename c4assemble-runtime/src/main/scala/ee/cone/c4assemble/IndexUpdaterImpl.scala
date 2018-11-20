package ee.cone.c4assemble

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class IndexUpdaterImpl() extends IndexUpdater {
  def setPart[K,V](worldKey: AssembledKey)(
    update: Future[IndexUpdate]
  ): WorldTransition⇒WorldTransition = transition ⇒ {
    val diff = new ReadModel(transition.diff.inner + (worldKey → update.map(_.diff)))
    val next = new ReadModel(transition.result.inner + (worldKey → update.map(_.result)))
    transition.copy(diff=diff,result=next)
  }
}
