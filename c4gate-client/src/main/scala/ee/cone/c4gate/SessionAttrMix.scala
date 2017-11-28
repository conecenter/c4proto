package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assembled

trait SessionAttrApp extends `The SessionDataProtocol`
  with SessionDataAssembleApp
  with `The SessionAttrAccessFactoryImpl`

trait SessionDataAssembleApp extends `The SessionDataAssemble` with `The SessionDataAssembles`