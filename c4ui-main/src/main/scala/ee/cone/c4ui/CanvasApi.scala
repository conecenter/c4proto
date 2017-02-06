package ee.cone.c4ui

import ee.cone.c4assemble.Types.World

trait CanvasContent {
  def value: String
  def until: Long
}

trait CanvasHandler extends  Product {
  def messageHandler: (String ⇒ String) ⇒ World ⇒ World
  def view: World⇒CanvasContent
}
