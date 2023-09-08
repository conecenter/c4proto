
package ee.cone.c4gate_server
import scala.annotation.tailrec
import java.nio.file._
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di._
import ee.cone.c4actor._

@c4("TopicToDirApp") final class TopicToDir(
  //consumerBeginningOffset: ConsumerBeginningOffset,
  consuming: Consuming,
  snapshotSaverFactory: SnapshotSaverFactory
)(
  txSaver: SnapshotSaver = snapshotSaverFactory.create("snapshot_txs")
) extends Executable with LazyLogging {
  def run(): Unit = {
    logger.info("begin")
    val beginningOffset = consuming.process("0" * OffsetHexSize(), _.beginningOffset)
    consuming.process(beginningOffset, consumer=>iteration(consumer))
    logger.info("end")
  }
  @tailrec private def iteration(consumer: Consumer): Unit = {
    val events = consumer.poll()
    val rangeStr = Seq(events.headOption,events.lastOption).flatten.map(_.srcId).mkString("..")
    logger.info(s"consumed ${events.size} ${rangeStr}")
    for(ev <- events) txSaver.save(ev.srcId, ev.data.toByteArray, ev.headers)
    logger.info("saved")
    iteration(consumer)
  }
}

@c4("TopicToDirApp") final class FileRawSnapshotSaver(dir: Path = Paths.get("/tmp")) extends RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit = {
    val path = dir.resolve(snapshot.relativePath)
    Files.createDirectories(path.getParent)
    Files.write(path, data)
  }
}