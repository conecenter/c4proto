package ee.cone.c4gate_server

import ee.cone.c4actor._
import ee.cone.c4di.c4

@c4("SimpleMakerApp") final class SimpleMakerExecutable(execution: Execution, snapshotMaker: SnapshotMaker) extends Executable {
  def run(): Unit = {
    val rawSnapshot :: _ = snapshotMaker.make(NextSnapshotTask(None))
    execution.complete()
  }
}

@c4("SimplePusherApp") final class SimplePusherExecutable(execution: Execution, snapshotLister: SnapshotLister, snapshotLoader: SnapshotLoader, rawQSender: RawQSender) extends Executable {
  def run(): Unit = {
    val snapshotInfo :: _ = snapshotLister.list
    val Some(event) = snapshotLoader.load(snapshotInfo.raw)
    assert(event.headers.isEmpty)
    rawQSender.send(List(new QRecord {
      def topic: TopicName = InboxTopicName()
      def value: Array[Byte] = event.data.toByteArray
      def headers: scala.collection.immutable.Seq[RawHeader] = event.headers
    }))
    execution.complete()
  }
}
