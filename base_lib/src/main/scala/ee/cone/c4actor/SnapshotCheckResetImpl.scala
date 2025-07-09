package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

@c4("ServerCompApp") final class SnapshotCheckResetImpl(
  s3: S3Manager, currentTxLogName: CurrentTxLogName, execution: Execution, snapshotSaver: SnapshotSaver,
  rawQSenderExecutable: RawQSenderExecutable, getRawQSender: DeferredSeq[RawQSender],
) extends SnapshotCheckReset {
  private val resetPath = "snapshots/.reset"
  def run(): Unit = if(execution.aWait(s3.get(s3.join(currentTxLogName, resetPath))(_)).nonEmpty){
    rawQSenderExecutable.run()
    val offset = Single(getRawQSender.value).send(new QRecord(Array.empty, Nil))
    val res = snapshotSaver.save(offset, Array.empty, Nil)
    if(!execution.aWait(s3.delete(s3.join(currentTxLogName, resetPath))(_))) throw new Exception(s"no $resetPath")
  }
}
//