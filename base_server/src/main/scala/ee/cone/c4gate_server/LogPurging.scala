
package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4di.c4

import scala.annotation.tailrec
import scala.concurrent.Future

@c4("SnapshotMakingApp") final class LogPurging(
  purging: QPurging,
  lister: SnapshotLister,
  keepLogForSnapshotCount: Int = 10,
  s3LogPurger: S3LogPurger,
  currentTxLogName: CurrentTxLogName,
) extends Executable {
  def run(): Unit =
    purging.process(currentTxLogName, purger => iteration(purger, None))
  @tailrec private def iteration(purger: QPurger, wasOffset: Option[NextOffset]): Unit = {
    val (need, noNeed) = lister.listWithMTime.sortBy(_.snapshot.offset).reverse
      .splitAt(keepLogForSnapshotCount)
    val willOffset = noNeed.headOption.map(_.snapshot.offset)
    if (wasOffset == willOffset) Thread.sleep(60000)
    else {
      willOffset.foreach(purger.delete(_))
      need.map(_.mTime).minOption.foreach{ mTime =>
        s3LogPurger.delete(currentTxLogName, mTime - 5*60*1000)
      }
    }
    iteration(purger,willOffset)
  }
}

@c4("SnapshotMakingApp") final class S3LogPurger(
  loBroker: LOBroker,
  s3: S3Manager,
  s3L: S3Lister,
  execution: Execution,
) extends LazyLogging {
  def delete(txLogName: TxLogName, beforeMillis: Long): Unit = execution.fatal{ implicit ec =>
    logger.debug(s"delete before ms: $beforeMillis")
    for{
      dataOpt <- s3.get(txLogName,loBroker.bucketPostfix)
      deleted <- Future.sequence(
        for {
          data <- dataOpt.toList
          (name,tStr) <- s3L.parseItems(data) if s3L.parseTime(tStr) < beforeMillis
        } yield s3.delete(txLogName,s"${loBroker.bucketPostfix}/$name")
      )
    } yield deleted
  }
}
