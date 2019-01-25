package ee.cone.c4gate

import java.nio.file.{Files, Path, Paths}
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.{Context, SleepUntilKey, TxTransform, WithPK}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}

object PurgerDefaultPolicy {
  def apply(): List[KeepPolicy] = {
    val millis = 1L
    val hour = 60L * 60L * 1000L * millis
    val day = 24L * hour
    val week = 7L * day
    List(KeepPolicy(millis, 8), KeepPolicy(hour, 8), KeepPolicy(day, 14), KeepPolicy(week, 14))
  }
}

case class KeepPolicy(period: Long, count: Int)

case class TimedPath(path: Path, mTime: Long)

class Purger(
  lister: SnapshotLister, baseDir: String
) extends LazyLogging {
  def process(keepPolicyList: List[KeepPolicy]/*todo: pass Loaded*/): Unit = {
    val files: List[TimedPath] = lister.list.map { snapshot ⇒
      val path = Paths.get(baseDir).resolve(snapshot.raw.relativePath)
      TimedPath(path, Files.getLastModifiedTime(path).toMillis)
    }
    val keepPaths = (for {
      keepPolicy ← keepPolicyList
      keepFile ← files.groupBy(file ⇒ file.mTime / keepPolicy.period).values
        .map(_.maxBy(_.mTime)).toList.sortBy(_.mTime).takeRight(keepPolicy.count)
    } yield keepFile.path).toSet

    for {
      path ← files.map(_.path).filterNot(keepPaths)
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

@assemble class PurgerAssemble(purger: Purger) extends Assemble {
  def joinPurger(
    key: SrcId,
    first: Each[Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(PurgerTx("purger",PurgerDefaultPolicy())(purger)))
}
