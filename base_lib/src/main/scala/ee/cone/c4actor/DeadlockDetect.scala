package ee.cone.c4actor

import java.lang.management.{ManagementFactory, ThreadMXBean}

import ee.cone.c4di.c4

import scala.annotation.tailrec

@c4("DeadlockDetectApp") final class DeadlockDetect extends Executable with Early {
  @tailrec def iter(bean: ThreadMXBean): Unit = Option(bean.findMonitorDeadlockedThreads()) match {
    case None =>
      Thread.sleep(1000)
      iter(bean)
    case Some(ids) =>
      val lines = for {
        info <- bean.getThreadInfo(ids,true,true).flatMap(Option(_))
        line <- Array(s"Id: ${info.getThreadId}") ++
          info.getLockedMonitors.map(s=>s"mon $s at ${s.getLockedStackFrame} depth ${s.getLockedStackDepth}") ++
          info.getStackTrace.map(s=>s"call $s")
      } yield line
      println(lines.map(l=>s"\nDD: $l").mkString)
      throw new Exception("Deadlock detected")
  }
  def run(): Unit = iter(ManagementFactory.getThreadMXBean)
}
