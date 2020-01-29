package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4di.c4

@c4("DumperApp") class Dumper(
  snapshotMaker: SnapshotMaker,
  snapshotLoader: SnapshotLoader,
  richRawWorldReducer: RichRawWorldReducer,
  execution: Execution,
  getS_HttpRequest: GetByPK[S_HttpRequest],
  getU_FromAlienState: GetByPK[U_FromAlienState],
) extends Executable {
  def run(): Unit = {
    val list = snapshotMaker.make(NextSnapshotTask(None))
    val event = snapshotLoader.load(list.head).get
    val context = richRawWorldReducer.reduce(None,List(event))
    getS_HttpRequest.ofA(context).values.toList.sortBy(_.srcId).foreach(println)
    getU_FromAlienState.ofA(context).values.toList.sortBy(_.sessionKey).foreach(println)
    execution.complete()
  }
}
