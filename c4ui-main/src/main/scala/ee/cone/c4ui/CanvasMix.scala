package ee.cone.c4ui

import ee.cone.c4assemble.{Assemble,`The Assemble`}

trait CanvasApp extends `The Assemble` {
  override def `the List of Assemble`: List[Assemble] = new CanvasAssemble :: super.`the List of Assemble`
}
