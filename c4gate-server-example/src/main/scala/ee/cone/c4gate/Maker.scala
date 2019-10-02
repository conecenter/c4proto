package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.c4component

@c4component("SimpleMakerApp") class SimpleMakerExecutable(execution: Execution, snapshotMaker: SnapshotMaker) extends Executable {
  def run(): Unit = {
    val rawSnapshot :: _ = snapshotMaker.make(NextSnapshotTask(None))
    execution.complete()
  }
}

@c4component("SimplePusherApp") class SimplePusherExecutable(execution: Execution, snapshotLister: SnapshotLister, snapshotLoader: SnapshotLoader, rawQSender: RawQSender) extends Executable {
  def run(): Unit = {
    val snapshotInfo :: _ = snapshotLister.list
    val Some(event) = snapshotLoader.load(snapshotInfo.raw)
    rawQSender.send(List(new QRecord {
      def topic: TopicName = InboxTopicName()
      def value: Array[Byte] = event.data.toByteArray
      def headers: scala.collection.immutable.Seq[RawHeader] = Nil
    }))
    execution.complete()
  }
}
