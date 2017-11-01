package ee.cone.c4actor

import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait BranchApp extends ProtocolsApp with `The BranchOperationsImpl` with `The BranchAssemble` {
  override def protocols: List[Protocol] = BranchProtocol :: super.protocols
}
