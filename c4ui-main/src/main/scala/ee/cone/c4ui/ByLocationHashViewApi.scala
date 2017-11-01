package ee.cone.c4ui

import ee.cone.c4actor.{TransientLens, c4component, listed}
import ee.cone.c4actor.Types.SrcId

@c4component @listed abstract class ByLocationHashView extends View

case object CurrentBranchKey extends TransientLens[SrcId]("")
