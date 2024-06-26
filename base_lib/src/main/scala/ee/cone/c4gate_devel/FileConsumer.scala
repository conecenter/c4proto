
package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble.Replace
import ee.cone.c4di._
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.jdk.CollectionConverters.IterableHasAsScala
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._

@c4("FileConsumerApp") final class FileConsuming(factory: FileConsumerFactory, dir: FileConsumerDir) extends Consuming {
  def process[R](from: NextOffset, body: Consumer=>R): R =
    body(factory.create(from, Files.readAllLines(dir.resolve("snapshot_tx_list")).asScala.map(RawSnapshot).toSeq))
}

@c4multi("FileConsumerApp") final class FileConsumer(from: NextOffset, list: Seq[RawSnapshot])(
  loader: SnapshotLoader, it: Iterator[RawSnapshot] = list.iterator
) extends Consumer {
  def poll(): List[RawEvent] =
    if(it.hasNext) loader.load(it.next()).filter(_.srcId >= from).toList
    else {
      Thread.sleep(Long.MaxValue)
      Nil
    }
  def endOffset: NextOffset = loader.load(list.last).get.srcId
}

@c4("FileConsumerApp") final class FileRawSnapshotLoader(
  dir: FileConsumerDir
) extends RawSnapshotLoader with LazyLogging {
  def load(snapshot: RawSnapshot): ByteString = {
    logger.info(snapshot.relativePath)
    ToByteString(Files.readAllBytes(dir.resolve(snapshot.relativePath)))
  }
}

@c4("FileConsumerApp") final class FileSnapshotLast(dir: FileConsumerDir) extends SnapshotLast {
  def get: Option[RawSnapshot] =
    Option(RawSnapshot(new String(Files.readAllBytes(dir.resolve("snapshot_name")), UTF_8)))
}

object InnerNoTxObserver extends Observer[RichContext] {
  def activate(world: RichContext): Observer[RichContext] = this
}

@c4("FileConsumerApp") final class ReportingTxObserver(replace: Replace) extends TxObserver(world=>{
    replace.report(world.assembled)
    InnerNoTxObserver
})

@c4("FileConsumerApp") final class DisablingProvider(){
  @provide def senders: Seq[RawQSenderExecutable] = Seq(()=>())
  @provide def disableDefObserver: Seq[DisableDefObserver] = Seq(new DisableDefObserver)
  @provide def disableDefaultKafkaConsuming: Seq[DisableDefConsuming] = Seq(new DisableDefConsuming)
  @provide def disableKafkaProducer: Seq[DisableDefProducer] = Seq(new DisableDefProducer)
}