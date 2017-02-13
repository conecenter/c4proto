package ee.cone.c4ui

import ee.cone.c4actor.{AssemblesApp, BranchApp, InitLocal, InitLocalsApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4assemble.Types.World
import ee.cone.c4vdom.{VDomLens, VDomState}
import ee.cone.c4vdom_mix.VDomApp

trait UIApp extends AlienExchangeApp with BranchApp with VDomApp with InitLocalsApp with AssemblesApp {
  type VDomStateContainer = World
  lazy val vDomStateKey: VDomLens[World,Option[VDomState]] = VDomStateKey
  //lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val sseUI = new UIInit(tags,tagStyles,vDomHandlerFactory,branchOperations)
  override def assembles: List[Assemble] = new VDomAssemble :: super.assembles
  override def initLocals: List[InitLocal] = sseUI :: DefaultUntilPolicyInit :: super.initLocals
}
