package ee.cone.c4gate

import ee.cone.c4actor._

// C4MAX_REQUEST_SIZE=30000000 C4INBOX_TOPIC_PREFIX='' C4BOOTSTRAP_SERVERS=localhost:8092 C4STATE_TOPIC_PREFIX=ee.cone.c4gate.SimpleMakerApp sbt 'c4gate-server-example/run-main ee.cone.c4actor.ServerMain'

class SimpleMakerApp extends RichDataApp with ExecutableApp
  with EnvConfigApp with VMExecutionApp
  with SnapshotMakingApp with NoAssembleProfilerApp with KafkaConsumerApp
{
  lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)

  override def toStart: List[Executable] = new SimpleMakerExecutable(execution,snapshotMaker) :: super.toStart
}

class SimpleMakerExecutable(execution: Execution, snapshotMaker: SnapshotMaker) extends Executable {
  def run(): Unit = {
    val Seq(rawSnapshot) = snapshotMaker.make(NextSnapshotTask(None))()
    execution.complete()
  }
}

class SimplePusherApp extends ExecutableApp with EnvConfigApp with VMExecutionApp with NoAssembleProfilerApp with KafkaProducerApp {
  private lazy val dbDir = "db4"
  private lazy val rawSnapshotLoader: RawSnapshotLoader = new FileRawSnapshotLoader(dbDir)
  private lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)
  override def toStart: List[Executable] = new SimplePusherExecutable(execution,snapshotLoader,rawQSender) :: super.toStart
}

class SimplePusherExecutable(execution: Execution, snapshotLoader: SnapshotLoader, rawQSender: RawQSender) extends Executable {
  def run(): Unit = {
    val Seq(snapshotInfo) = snapshotLoader.list
    val Some(event) = snapshotLoader.load(snapshotInfo.raw)
    rawQSender.send(List(new QRecord {
      def topic: TopicName = InboxTopicName()
      def value: Array[Byte] = event.data.toByteArray
    }))
    execution.complete()
  }
}
