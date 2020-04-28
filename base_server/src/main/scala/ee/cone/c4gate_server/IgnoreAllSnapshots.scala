package ee.cone.c4gate_server

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4di.c4

@c4("IgnoreAllSnapshotsApp") final class IgnoreAllSnapshots(
  toUpdate: ToUpdate,
  consuming: Consuming,
  factory: SnapshotSaverImplFactory,
  baseDir: DataDir,
) extends Executable with LazyLogging {
  private def ignoreTheSamePath(path: Path): Unit = ()
  def run(): Unit = {
    val endOffset = consuming.process("0" * OffsetHexSize(), _.endOffset)
    val subDir = "snapshots"
    val path = Paths.get(baseDir.value).resolve(subDir)
    if(Files.exists(path))
      ignoreTheSamePath(Files.move(path,path.resolveSibling(s"$subDir.${UUID.randomUUID()}.bak")))
    val (bytes, headers) = toUpdate.toBytes(Nil)
    // val saver = snapshotSavers.full
    val saver = factory.create(subDir)
    val rawSnapshot = saver.save(endOffset, bytes, headers)
    logger.info(s"EMPTY snapshot was saved: ${rawSnapshot.relativePath}")

  }
}
