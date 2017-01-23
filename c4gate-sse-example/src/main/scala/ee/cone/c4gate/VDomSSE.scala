package ee.cone.c4gate

import ee.cone.c4actor.{InitLocal, InitLocalsApp}
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4vdom
import ee.cone.c4vdom.{ChildPair, CurrentVDom, VDomLens, VDomState}
import ee.cone.c4vdom_mix.VDomApp

trait VDomSSEApp extends BranchApp with VDomApp with InitLocalsApp {
  type VDomStateContainer = World
  lazy val vDomStateKey: VDomLens[World,Option[VDomState]] = VDomStateKey
  lazy val relocateKey: VDomLens[World, String] = RelocateKey
  //private lazy val sseUI = new TestVDomUI(currentVDom)
  override def initLocals: List[InitLocal] = sseUI :: super.initLocals
}

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with VDomLens[World, Option[VDomState]]
case object RelocateKey extends WorldKey[String]("")
  with VDomLens[World, String]

/*
class TestVDomUI(currentVDom: CurrentVDom[World]) extends InitLocal {
  def initLocal: World ⇒ World =
    FromAlienKey.set(currentVDom.fromAlien)
    .andThen(ToAlienKey.set(currentVDom.toAlien))
}
*/

////

trait View {
  def view: World ⇒ List[ChildPair[_]]
}