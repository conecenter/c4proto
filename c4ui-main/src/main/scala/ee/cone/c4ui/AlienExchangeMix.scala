package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.{AlienProtocol, HttpProtocol}
import ee.cone.c4proto.Protocol

trait AlienExchangeApp extends InitLocalsApp with ProtocolsApp with AssemblesApp {
  def branchOperations: BranchOperations
  //
  override def initLocals: List[InitLocal] = SendToAlienInit :: super.initLocals
  override def assembles: List[Assemble] =
    new FromAlienBranchAssemble(branchOperations) ::
    new MessageFromAlienAssemble ::
    super.assembles
  override def protocols: List[Protocol] = HttpProtocol :: AlienProtocol :: super.protocols
}
