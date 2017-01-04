package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait SSEApp extends ProtocolsApp with AssemblesApp {
  def sseUI: SSEui
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  override def assembles: List[Assemble] = new SSEAssemble(sseUI) :: super.assembles
}
