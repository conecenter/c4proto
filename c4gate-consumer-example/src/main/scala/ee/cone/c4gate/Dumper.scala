package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.HttpProtocol.S_HttpPost
import ee.cone.c4proto.Protocol

class DumperApp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
  with ToStartApp
  with EnvConfigApp
{
  lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)
  override def protocols: List[Protocol] = HttpProtocol :: AlienProtocol :: super.protocols
  override def toStart: List[Executable] = new Dumper(snapshotMaker,snapshotLoader,richRawWorldReducer,execution) :: super.toStart
}

class Dumper(
  snapshotMaker: SnapshotMaker,
  snapshotLoader: SnapshotLoader,
  richRawWorldReducer: RichRawWorldReducer,
  execution: Execution
) extends Executable {
  def run(): Unit = {
    val list = snapshotMaker.make(NextSnapshotTask(None))
    val event = snapshotLoader.load(list.head).get
    val context = richRawWorldReducer.reduce(None,List(event))
    ByPK(classOf[S_HttpPost]).of(context).values.toList.sortBy(_.srcId).foreach(println)
    ByPK(classOf[FromAlienState]).of(context).values.toList.sortBy(_.sessionKey).foreach(println)
    execution.complete()
  }
}
