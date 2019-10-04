package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4proto.c4

@c4("DumperApp") class Dumper(
  snapshotMaker: SnapshotMaker,
  snapshotLoader: SnapshotLoader,
  richRawWorldReducer: RichRawWorldReducer,
  execution: Execution
) extends Executable {
  def run(): Unit = {
    val list = snapshotMaker.make(NextSnapshotTask(None))
    val event = snapshotLoader.load(list.head).get
    val context = richRawWorldReducer.reduce(None,List(event))
    ByPK(classOf[S_HttpRequest]).of(context).values.toList.sortBy(_.srcId).foreach(println)
    ByPK(classOf[U_FromAlienState]).of(context).values.toList.sortBy(_.sessionKey).foreach(println)
    execution.complete()
  }
}
