package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble

trait SessionAttrApp extends `The SessionDataProtocol`
  with SessionDataAssembleApp
  with `The SessionAttrAccessFactoryImpl`

trait SessionDataAssembleApp extends `The SessionDataAssemble` {
  def `the MortalFactory`: MortalFactory

  override def `the List of Assemble`: List[Assemble] =
    SessionDataAssembles(`the MortalFactory`) ::: super.`the List of Assemble`
}
