package ee.cone.c4gate_server

import java.nio.file.{Files, Path, Paths}
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.{Context, SleepUntilKey, TxTransform, WithPK}
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
    List(KeepPolicy(millis, 8), KeepPolicy(hour, 23), KeepPolicy(day, 14), KeepPolicy(week, 14))
  }
}

case class KeepPolicy(period: Long, count: Int)

case class TimedPath(path: Path, mTime: Long)

trait Purger {
  def process(keepPolicyList: List[KeepPolicy]): Unit
}

@c4("SnapshotMakingApp") final class PurgerImpl(
  lister: SnapshotLister, baseDir: DataDir
) extends Purger with LazyLogging {
  def process(keepPolicyList: List[KeepPolicy]/*todo: pass Loaded*/): Unit = {
    val files: List[TimedPath] = lister.list.map { snapshot =>
      val path = Paths.get(baseDir.value).resolve(snapshot.raw.relativePath)
      TimedPath(path, Files.getLastModifiedTime(path).toMillis)
    }
    val keepPaths = (for {
      keepPolicy <- keepPolicyList
      keepFile <- files.groupBy(file => file.mTime / keepPolicy.period).values
        .map(_.maxBy(_.mTime)).toList.sortBy(_.mTime).takeRight(keepPolicy.count)
    } yield keepFile.path).toSet

    for {
      path <- files.map(_.path).filterNot(keepPaths)
    } {
      Files.deleteIfExists(path)
      logger.info(s"removed $path")
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

@c4assemble("SnapshotMakingApp") class PurgerAssembleBase(purger: Purger)   {
  def joinPurger(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(PurgerTx("purger",PurgerDefaultPolicy())(purger)))
}
