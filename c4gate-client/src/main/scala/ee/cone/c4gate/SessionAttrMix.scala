package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble,`The Assemble`}
import ee.cone.c4proto.Protocol

trait SessionAttrApp extends SessionDataProtocolApp
  with SessionDataAssembleApp
  with `The SessionAttrAccessFactoryImpl`

trait SessionDataProtocolApp extends ProtocolsApp {
  override def protocols: List[Protocol] =
    SessionDataProtocol :: super.protocols
}

trait SessionDataAssembleApp extends `The Assemble` {
  def `the MortalFactory`: MortalFactory

  override def `the List of Assemble`: List[Assemble] =
    SessionDataAssembles(`the MortalFactory`) ::: super.`the List of Assemble`
}
