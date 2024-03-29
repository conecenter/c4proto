
package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

@c4("SimplePusherApp") final class SimplePusherExecutable(
  execution: Execution, snapshotLister: SnapshotLister,
  snapshotLoader: SnapshotLoader, rawQSender: RawQSender,
  currentTxLogName: CurrentTxLogName,
) extends Executable with LazyLogging {
  def run(): Unit = {
    val snapshotInfo :: _ = snapshotLister.list
    val Some(event) = snapshotLoader.load(snapshotInfo.raw)
    assert(event.headers.isEmpty)
    val offset = rawQSender.send(new QRecord {
      def topic: TxLogName = currentTxLogName
      def value: Array[Byte] = event.data.toByteArray
      def headers: scala.collection.immutable.Seq[RawHeader] = event.headers
    })
    logger.info(s"pushed $offset")
    execution.complete()
  }
}
