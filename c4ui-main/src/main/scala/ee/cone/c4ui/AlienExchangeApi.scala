package ee.cone.c4ui

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.AlienProtocol.FromAlienState

case class FromAlienTask(branchKey: SrcId, fromAlienState: FromAlienState, locationHash: String)

