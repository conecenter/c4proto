package ee.cone.c4ui

import ee.cone.c4actor.TransientLens
import ee.cone.c4actor.Types.SrcId

class c4component
class listed

@c4component @listed
trait ByLocationHashView extends View

case object CurrentBranchKey extends TransientLens[SrcId]("")

trait ByLocationHashViewsApp {
  def byLocationHashViews: List[ByLocationHashView] = Nil
}
