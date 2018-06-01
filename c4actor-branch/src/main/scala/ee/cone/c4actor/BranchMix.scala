package ee.cone.c4actor

import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait BranchApp extends ProtocolsApp with AssemblesApp {
  def qAdapterRegistry: QAdapterRegistry
  def uuidUtil: UUIDUtil
  //
  lazy val branchOperations: BranchOperations = new BranchOperationsImpl(qAdapterRegistry,uuidUtil)
  override def protocols: List[Protocol] = BranchProtocol :: super.protocols
  override def assembles: List[Assemble] = new BranchAssemble(qAdapterRegistry,branchOperations) :: super.assembles
}
