package ee.cone.c4ui

import ee.cone.c4actor.BranchMessage
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

case object CanvasContentKey extends WorldKey[Option[CanvasContent]](None)

trait CanvasContent {
  def value: String
  def until: Long
}

trait CanvasHandler extends  Product {
  def messageHandler: BranchMessage ⇒ World ⇒ World
  def view: World⇒CanvasContent
}
