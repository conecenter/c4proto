package ee.cone.c4actor.sandbox

import ee.cone.c4actor.AssemblesApp
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.sandbox.SandboxProtocol.SandboxOrig
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}

/*
  This file can be edited for learning purposes, feel free to experiment here
 */

trait SandboxJoinersApp
  extends AssemblesApp {
  override def assembles: List[Assemble] = new SandboxJoiners :: super.assembles
}

case class SandboxRich(srcId: String, value: Int)

@assemble class SandboxJoiners extends Assemble {

  def SandboxOrigToSandBoxRich(
    srcId: SrcId,
    sandboxOrigs: Values[SandboxOrig]
  ): Values[(SrcId, SandboxRich)] =
    sandboxOrigs
      .map(orig ⇒ SandboxRich(orig.srcId, orig.value))
      .map(rich ⇒ (rich.srcId, rich))

  
}