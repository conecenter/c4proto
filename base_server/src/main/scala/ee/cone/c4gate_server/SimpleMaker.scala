package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

@c4("SimpleMakerApp") final class SimpleMakerExecutable(execution: Execution, snapshotMaker: SnapshotMaker) extends Executable {
  def run(): Unit = {
    val rawSnapshot :: _ = snapshotMaker.make(NextSnapshotTask(None))
    //execution.complete()
  }
}
