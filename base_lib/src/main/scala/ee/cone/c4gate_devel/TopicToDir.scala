
package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4di._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._
import scala.annotation.tailrec

@c4("TopicToDirApp") final class TopicToDir(
  //consumerBeginningOffset: ConsumerBeginningOffset,
  consuming: Consuming,
  snapshotSaverFactory: SnapshotSaverFactory
)(
  txSaver: SnapshotSaver = snapshotSaverFactory.create("snapshot_txs")
) extends Executable with LazyLogging {
  def run(): Unit = {
    logger.info("begin")
    val offset = consuming.process("0" * OffsetHexSize(), _.endOffset)
    consuming.process(offset, consumer=>iteration(consumer))
    logger.info("end")
  }
  @tailrec private def iteration(consumer: Consumer): Unit = {
    val events = consumer.poll()
    val rangeStr = Seq(events.headOption,events.lastOption).flatten.map(_.srcId).mkString("..")
    logger.debug(s"consumed ${events.size} ${rangeStr}")
    for(ev <- events) txSaver.save(ev.srcId, ev.data.toByteArray, ev.headers)
    logger.debug("saved")
    iteration(consumer)
  }
}

@c4("TopicToDirApp") final class PurgeTopicToDir extends Executable with LazyLogging {
  def run(): Unit = iteration()
  @tailrec private def iteration(): Unit = {
    val cmd = Seq("find", "/tmp/snapshot_txs", "-mmin", "+10080", "-type", "f") // 1 week
    val filesA = new String(new ProcessBuilder(cmd: _*).start().getInputStream.readAllBytes(), UTF_8).split("\n")
    for(file <- filesA if file.nonEmpty){
      logger.debug(s"deleting $file")
      Files.delete(Paths.get(file))
      logger.debug(s"deleted")
    }
    Thread.sleep(1000*60*30)
    iteration()
  }
}

@c4("TopicToDirApp") final class FileRawSnapshotSaver(dir: Path = Paths.get("/tmp")) extends RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit = {
    val path = dir.resolve(snapshot.relativePath)
    Files.createDirectories(path.getParent)
    Files.write(path, data)
  }
}