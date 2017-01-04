package ee.cone.c4gate

import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4vdom
import ee.cone.c4vdom.{CurrentVDom, VDomState}
import ee.cone.c4vdom_mix.VDomApp

trait VDomSSEApp extends SSEApp with VDomApp {
  def allowOriginOption: Option[String]
  //
  type VDomStateContainer = World
  lazy val vDomStateKey: c4vdom.VDomLens[World,Option[VDomState]] = VDomStateKey
  lazy val sseUI: SSEui = new TestVDomUI(currentVDom, allowOriginOption, "/connection")
}

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with c4vdom.VDomLens[World, Option[VDomState]]

class TestVDomUI(
  currentVDom: CurrentVDom[World],
  val allowOriginOption: Option[String],
  val postURL: String
) extends SSEui {
  def fromAlien: (String ⇒ Option[String]) ⇒ World ⇒ World = currentVDom.fromAlien
  def toAlien: World ⇒ (World, List[(String, String)]) = currentVDom.toAlien
}
