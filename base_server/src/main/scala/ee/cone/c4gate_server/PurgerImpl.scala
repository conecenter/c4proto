package ee.cone.c4gate_server

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4actor.Types.{SrcId, TxEvents}
import ee.cone.c4di.c4

object PurgerDefaultPolicy {
  def apply(): List[KeepPolicy] = {
    val millis = 1L
    val hour = 60L * 60L * 1000L * millis
    val day = 24L * hour
    val week = 7L * day
    val month = 4L * week
    List(KeepPolicy(millis, 8), KeepPolicy(hour, 24*3), KeepPolicy(day, 14), KeepPolicy(week, 14), KeepPolicy(month, 9))
  }
}

case class KeepPolicy(period: Long, count: Int)

trait Purger {
  def process(keepPolicyList: List[KeepPolicy]): Unit
}

@c4("SnapshotMakingApp") final class PurgerImpl(
  lister: SnapshotLister, remover: SnapshotRemover
) extends Purger with LazyLogging {
  def process(keepPolicyList: List[KeepPolicy]/*todo: pass Loaded*/): Unit = {
    val files: List[TimedSnapshotInfo] = lister.listWithMTime
    val keep = (for {
      keepPolicy <- keepPolicyList
      keepFile <- files.groupBy(file => file.mTime / keepPolicy.period).values
        .map(_.maxBy(_.mTime)).toList.sortBy(_.mTime).takeRight(keepPolicy.count)
    } yield keepFile.snapshot).toSet

    for {
      path <- files.map(_.snapshot).filterNot(keep)
    } {
      if(remover.deleteIfExists(path)) logger.info(s"removed $path")
    }
    logger.debug("snapshots checked")
  }
}

@c4("SnapshotMakingApp") final case class PurgerTx(srcId: SrcId = "purger")(
  purger: Purger, config: Config, sleep: Sleep,
)(
  policy: List[KeepPolicy] = {
    val value = config.get("C4KEEP_SNAPSHOTS")
    val res = if(value == "default") PurgerDefaultPolicy()
      else (for(pair <- value.split(",").toList) yield {
        val Array(periodStr,countStr) = pair.split("x")
        KeepPolicy(periodStr.toLong,countStr.toInt)
      })
    assert(res.nonEmpty)
    res
  }
) extends SingleTxTr {
  def transform(local: Context): TxEvents = {
    purger.process(policy)
    sleep.forSeconds(60L)
  }
}
