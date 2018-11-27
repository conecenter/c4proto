package ee.cone.c4gate.purger

import java.nio.file.{Files, Path, Paths}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._

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

class PurgerExecutable(
  lister: RawSnapshotLister, baseDir: String, keepPolicyList: List[KeepPolicy]
) extends Executable with LazyLogging {
  def run(): Unit = {
    while (true) {
      val files: List[TimedPath] = lister.list("snapshots").map { rawSnapshot ⇒
        val path = Paths.get(baseDir).resolve(rawSnapshot.relativePath)
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
      Thread.sleep(60L * 1000L)
    }
  }
}

trait PurgerApp extends ExecutableApp
  with VMExecutionApp
  with ToStartApp {
  def rawSnapshotLister: RawSnapshotLister

  def dbDir: String

  override def toStart: List[Executable] = new PurgerExecutable(rawSnapshotLister, dbDir, PurgerDefaultPolicy()) :: super.toStart
}
