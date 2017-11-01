package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.{AlienProtocol, HttpProtocol}
import ee.cone.c4proto.Protocol

trait AlienExchangeApp extends ToInjectApp with ProtocolsApp with AssemblesApp with `The FromAlienBranchAssemble` {
  override def toInject: List[ToInject] = SendToAlienInit :: super.toInject
  override def assembles: List[Assemble] =
    new MessageFromAlienAssemble ::
    super.assembles
  override def protocols: List[Protocol] = HttpProtocol :: AlienProtocol :: super.protocols
}
