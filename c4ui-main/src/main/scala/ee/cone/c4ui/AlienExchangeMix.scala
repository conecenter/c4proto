package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4gate._

trait AlienExchangeApp extends `The ToInject` with `The HttpProtocol` with `The AlienProtocol`
  with `The FromAlienBranchAssemble` with `The MessageFromAlienAssemble` with `The SendToAlienInit`
