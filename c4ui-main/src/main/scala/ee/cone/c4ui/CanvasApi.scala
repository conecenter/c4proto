package ee.cone.c4ui

import ee.cone.c4actor._

case object CanvasContentKey extends TransientLens[Option[CanvasContent]](None)

trait CanvasContent {
  def value: String

  def until: Long
}

trait CanvasHandler extends Product {
  def messageHandler: BranchMessage ⇒ Context ⇒ Context

  def view: Context ⇒ CanvasContent
}
