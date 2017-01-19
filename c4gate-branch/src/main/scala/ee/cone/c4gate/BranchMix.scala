package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait BranchApp extends ProtocolsApp with AssemblesApp {
  def qAdapterRegistry: QAdapterRegistry
  private lazy val branchOperations = new BranchOperations(qAdapterRegistry)
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def assembles: List[Assemble] = new BranchAssemble(branchOperations) :: super.assembles
}
