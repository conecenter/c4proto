package ee.cone.c4ui


import ee.cone.c4actor._
import ee.cone.c4vdom_mix.VDomApp

trait UIApp extends AlienExchangeApp with BranchApp with VDomApp with `The VDomAssemble`
  with `The DefaultUntilPolicy` with `The UIInit`
