package ee.cone.c4actor

import java.lang.management.{ManagementFactory, ThreadMXBean}
import java.util.UUID

import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4actor.SystemProblemReportProtocol.S_SystemProblemReport
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.HttpUtil
import ee.cone.c4proto.{Id, ToByteString, protocol}

import scala.annotation.tailrec

@c4("DeadlockDetectApp") final class DeadlockDetect(
  reporters: List[SystemProblemReporter],
  execution: Execution
) extends Executable with Early {
  @tailrec def iter(bean: ThreadMXBean): Unit = Option(bean.findMonitorDeadlockedThreads()) match {
    case None =>
      Thread.sleep(1000)
      iter(bean)
    case Some(ids) =>
      val linesArray = for {
        info <- bean.getThreadInfo(ids,true,true).flatMap(Option(_))
        line <- Array(s"Id: ${info.getThreadId}") ++
          info.getLockedMonitors.map(s=>s"mon $s at ${s.getLockedStackFrame} depth ${s.getLockedStackDepth}") ++
          info.getStackTrace.map(s=>s"at $s")
      } yield line
      val lines = linesArray.toList
      println(lines.map(l=>s"\nDD: $l").mkString)
      reporters.foreach(_.report(lines))
      throw new Exception("Deadlock detected")
  }
  def run(): Unit = iter(ManagementFactory.getThreadMXBean)
}

//// move if using outside

trait SystemProblemReporter {
  def report(lines: List[String]): Unit // must not block infinitely or no exit will happen
}

@protocol("DeadlockDetectApp") object SystemProblemReportProtocol   {
  @Id() case class S_SystemProblemReport(
    @Id() srcId: String,
    @Id() lines: List[String],
  )
}

@c4("DeadlockDetectApp") final class ToGateSystemProblemReporter(
  idGenUtil: IdGenUtil,
  toUpdate: ToUpdate,
) extends SystemProblemReporter {
  def report(lines: List[String]): Unit = {
    val srcId = idGenUtil.srcIdFromStrings(UUID.randomUUID.toString)
    val report = S_SystemProblemReport(srcId,lines)
    val updates = LEvent.update(report).map(toUpdate.toUpdate).toList
  }
}

//// move if using outside

trait ToGateRawUpdateSender {
  def send(updates: List[N_Update]): Unit
}

@c4("DeadlockDetectApp") final class ToGateRawUpdateSenderImpl(
  toUpdate: ToUpdate,
  snapshotSaverFactory: SnapshotSaverFactory,
  factory: ToGateInnerRawUpdateSenderFactory,
  getOffset: GetOffset,
) extends ToGateRawUpdateSender {
  def send(updates: List[N_Update]): Unit = {
    val (bytes, headers) = toUpdate.toBytes(updates)
    val offset = getOffset.empty
    val rawSaver = factory.create()
    val saver = snapshotSaverFactory.create("snapshot_txs",rawSaver)
    val _ = saver.save(offset,bytes,headers)
  }
}

@c4multi("DeadlockDetectApp") final class ToGateInnerRawUpdateSender()(
  config: Config,
  signer: Signer[List[String]],
  util: HttpUtil,
) extends RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit = {
    val url = "/raw-update"
    val baseURL = config.get("C4HTTP_SERVER")
    val until = System.currentTimeMillis() + 10*1000
    val signed = signer.sign(List(url,snapshot.relativePath),until)
    val headers = ("x-r-signed",signed) :: Nil
    util.post(s"$baseURL$url",headers,ToByteString(data),Option(5000),200)
  }
}
