package ee.cone.c4ui


import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4vdom.{VDomLens, VDomState}
import ee.cone.c4vdom_mix.VDomApp

trait UIApp extends AlienExchangeApp with BranchApp with VDomApp with `The ToInject` with `The Assemble` with `The DefaultUntilPolicy` {
  type VDomStateContainer = Context
  lazy val vDomStateKey: VDomLens[Context,Option[VDomState]] = VDomStateKey
  //lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val sseUI = new UIInit(`the VDomHandlerFactory`)
  override def `the List of Assemble`: List[Assemble] = new VDomAssemble :: super.`the List of Assemble`
  override def `the List of ToInject`: List[ToInject] = sseUI :: super.`the List of ToInject`
}
