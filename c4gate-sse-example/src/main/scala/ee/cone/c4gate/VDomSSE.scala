package ee.cone.c4gate

import ee.cone.c4actor.{InitLocal, InitLocalsApp}
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4vdom
import ee.cone.c4vdom.{CurrentVDom, VDomState}
import ee.cone.c4vdom_mix.VDomApp

trait VDomSSEApp extends SSEApp with VDomApp with InitLocalsApp {
  def allowOriginOption: Option[String]
  //
  type VDomStateContainer = World
  lazy val vDomStateKey: c4vdom.VDomLens[World,Option[VDomState]] = VDomStateKey
  private lazy val sseUI = new TestVDomUI(currentVDom)
  override def initLocals: List[InitLocal] =
    sseUI :: NoProxySSEConfig :: super.initLocals
}

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with c4vdom.VDomLens[World, Option[VDomState]]

class TestVDomUI(currentVDom: CurrentVDom[World]) extends InitLocal {
  def initLocal: World ⇒ World =
    FromAlienKey.set(currentVDom.fromAlien)
    .andThen(ToAlienKey.set(currentVDom.toAlien))
}
