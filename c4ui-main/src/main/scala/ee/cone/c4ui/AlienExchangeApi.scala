package ee.cone.c4ui

import ee.cone.c4actor.BranchTask
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assembled
import ee.cone.c4gate.AlienProtocol.FromAlienState

case class FromAlienTask(
  branchKey: SrcId,
  branchTask: BranchTask,
  fromAlienState: FromAlienState,
  locationQuery: String,
  locationHash: String
)

trait FromAlienTaskAssembleFactory {
  def apply(file: String): Assembled
}