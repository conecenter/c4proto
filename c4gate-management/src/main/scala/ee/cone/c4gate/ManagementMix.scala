package ee.cone.c4gate

import ee.cone.c4actor.{ActorName, AssemblesApp}
import ee.cone.c4assemble.Assemble

trait ManagementApp extends AssemblesApp {
  def mainActorName: ActorName

  override def assembles: List[Assemble] =
    new ManagementPostAssemble(mainActorName) :: super.assembles
}
