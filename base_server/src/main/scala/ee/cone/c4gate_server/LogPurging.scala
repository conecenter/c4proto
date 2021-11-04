
package ee.cone.c4gate_server

import ee.cone.c4actor.{Executable, QPurger, QPurging}
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4di.c4

import scala.annotation.tailrec

@c4("SnapshotMakingApp") final class LogPurging(
  purging: QPurging,
  lister: SnapshotLister,
  keepLogForSnapshotCount: Int = 10
) extends Executable {
  def run(): Unit = purging.process{ purger => iteration(purger, None) }
  @tailrec private def iteration(purger: QPurger, wasOffset: Option[NextOffset]): Unit = {
    val willOffset =
      lister.list.map(_.offset).sorted.reverse.drop(keepLogForSnapshotCount).headOption
    if (wasOffset == willOffset) Thread.sleep(60000)
    else willOffset.foreach(purger.delete(_))
    iteration(purger,willOffset)
  }
}
