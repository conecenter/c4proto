package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.{AlienProtocolApp, HttpProtocolApp}

trait AlienExchangeApp extends ToInjectApp with AssemblesApp with AlienProtocolApp with HttpProtocolApp {
  def branchOperations: BranchOperations
  //
  override def toInject: List[ToInject] = SendToAlienInit :: super.toInject
  override def assembles: List[Assemble] =
    new FromAlienBranchAssemble(branchOperations) ::
    new MessageFromAlienAssemble ::
    super.assembles
}
