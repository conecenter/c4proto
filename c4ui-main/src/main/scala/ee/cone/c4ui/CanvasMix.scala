package ee.cone.c4ui

import ee.cone.c4actor.AssemblesApp
import ee.cone.c4assemble.Assemble

trait CanvasApp extends AssemblesApp {
  override def assembles: List[Assemble] = new CanvasAssemble :: super.assembles
}
