package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait SessionAttrApp extends SessionDataProtocolApp
  with SessionDataAssembleApp
  with `The SessionAttrAccessFactoryImpl`

trait SessionDataProtocolApp extends ProtocolsApp {
  override def protocols: List[Protocol] =
    SessionDataProtocol :: super.protocols
}

trait SessionDataAssembleApp extends AssemblesApp {
  def `the MortalFactory`: MortalFactory

  override def assembles: List[Assemble] =
    SessionDataAssembles(`the MortalFactory`) ::: super.assembles
}
