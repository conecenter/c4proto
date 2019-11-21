package ee.cone.c4ui

import ee.cone.c4actor_branch.BranchTask
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.AlienProtocol.U_FromAlienState

case class FromAlienTask(
  branchKey: SrcId,
  branchTask: BranchTask,
  fromAlienState: U_FromAlienState,
  locationQuery: String,
  locationHash: String
)
