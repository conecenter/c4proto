package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4proto.Protocol

class DumperApp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
  with ToStartApp
{
  override def protocols: List[Protocol] = HttpProtocol :: AlienProtocol :: super.protocols
  override def toStart: List[Executable] = new Dumper(rawSnapshot,execution) :: super.toStart
}

class Dumper(
  rawSnapshot: RawSnapshot,
  execution: Execution
) extends Executable {
  def run(): Unit = {
    val context = rawSnapshot.loadRecent() match { case world: RichContext ⇒ world }
    ByPK(classOf[HttpPost]).of(context).values.toList.sortBy(_.srcId).foreach(println)
    ByPK(classOf[FromAlienState]).of(context).values.toList.sortBy(_.sessionKey).foreach(println)
    execution.complete()
  }
}