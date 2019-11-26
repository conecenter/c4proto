package ee.cone.c4ui

import ee.cone.c4actor.TransientLens
import ee.cone.c4actor.Types.SrcId

trait ByLocationHashView extends View

case object CurrentBranchKey extends TransientLens[SrcId]("")

case object CurrentPathKey extends TransientLens[SrcId]("")
