package ee.cone.c4actor

import ee.cone.c4assemble.Assemble

trait BranchApp extends BranchAutoApp with AssemblesApp {
  def qAdapterRegistry: QAdapterRegistry
  def idGenUtil: IdGenUtil
  //
  lazy val branchOperations: BranchOperations = new BranchOperationsImpl(qAdapterRegistry,idGenUtil)
  override def assembles: List[Assemble] = new BranchAssemble(qAdapterRegistry,branchOperations) :: super.assembles
}
