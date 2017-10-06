package ee.cone.c4ui

import ee.cone.c4actor.AssemblesApp
import ee.cone.c4assemble.Assemble

trait PublicViewAssembleApp extends AssemblesApp {
  def byLocationHashViews: List[ByLocationHashView]
  override def assembles: List[Assemble] =
    new PublicViewAssemble(byLocationHashViews) :: super.assembles
}
