package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble,`The Assemble`}
import ee.cone.c4gate.{AlienProtocol, HttpProtocol}
import ee.cone.c4proto.Protocol

trait AlienExchangeApp extends `The ToInject` with ProtocolsApp with `The Assemble` with `The FromAlienBranchAssemble` {
  override def `the List of ToInject`: List[ToInject] = SendToAlienInit :: super.`the List of ToInject`
  override def `the List of Assemble`: List[Assemble] =
    new MessageFromAlienAssemble ::
    super.`the List of Assemble`
  override def protocols: List[Protocol] = HttpProtocol :: AlienProtocol :: super.protocols
}
