package ee.cone.c4gate

import ee.cone.c4actor._

class SimpleMakerApp extends RichDataApp
  with EnvConfigApp with VMExecutionApp
  with SnapshotMakingApp with NoAssembleProfilerApp with KafkaConsumerApp
{
  lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)

  override def toStart: List[Executable] = new SimpleMakerExecutable(execution,snapshotMaker) :: super.toStart
}

class SimpleMakerExecutable(execution: Execution, snapshotMaker: SnapshotMaker) extends Executable {
  def run(): Unit = {
    val Seq(rawSnapshot) = snapshotMaker.make(NextSnapshotTask(None))
    execution.complete()
  }
}