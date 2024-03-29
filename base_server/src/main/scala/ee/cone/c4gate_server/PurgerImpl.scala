package ee.cone.c4gate_server

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, c4assemble}
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

case class PurgerTx(
  srcId: SrcId, keepPolicyList: List[KeepPolicy]
)(purger: Purger) extends TxTransform {
  def transform(local: Context): Context = {
    purger.process(keepPolicyList)
    SleepUntilKey.set(Instant.now.plusSeconds(60L))(local)
  }
}

@c4assemble("SnapshotMakingApp") class PurgerAssembleBase(
  purger: Purger,
  config: Config,
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
){
  def joinPurger(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(PurgerTx("purger",policy)(purger)))
}
