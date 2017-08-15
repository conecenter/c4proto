package ee.cone.c4ui


import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4vdom.{VDomLens, VDomState}
import ee.cone.c4vdom_mix.VDomApp

trait UIApp extends AlienExchangeApp with BranchApp with VDomApp with ToInjectApp with AssemblesApp {
  type VDomStateContainer = Context
  lazy val vDomStateKey: VDomLens[Context,Option[VDomState]] = VDomStateKey
  //lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val sseUI = new UIInit(tags,tagStyles,vDomHandlerFactory,branchOperations)
  override def assembles: List[Assemble] = new VDomAssemble :: super.assembles
  override def toInject: List[ToInject] = sseUI :: DefaultUntilPolicyInit :: super.toInject
}
