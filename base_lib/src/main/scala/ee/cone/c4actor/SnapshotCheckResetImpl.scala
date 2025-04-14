package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

@c4("ServerCompApp") final class SnapshotCheckResetImpl(
  s3: S3Manager, currentTxLogName: CurrentTxLogName, execution: Execution, snapshotSaverFactory: SnapshotSaverFactory,
  rawQSenderExecutable: RawQSenderExecutable, getRawQSender: DeferredSeq[RawQSender],
) extends SnapshotCheckReset {
  private val resetPath = "snapshots/.reset"
  def run(): Unit = if(execution.aWait(s3.get(currentTxLogName, resetPath)(_)).nonEmpty){
    rawQSenderExecutable.run()
    val offset = Single(getRawQSender.value).send(new QRecord {
      def topic: TxLogName = currentTxLogName
      def value: Array[Byte] = Array.empty
      def headers: Seq[RawHeader] = Nil
    })
    val snapshotSaver = snapshotSaverFactory.create("snapshots")
    snapshotSaver.save(offset, Array.empty, Nil)
    if(!execution.aWait(s3.delete(currentTxLogName, resetPath)(_))) throw new Exception(s"no $resetPath")
  }
}
//