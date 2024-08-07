package ee.cone.c4ui.dep

import ee.cone.c4actor.Access
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.{AskByPK, Dep}
import ee.cone.c4actor.time.{CurrentTime, T_Time}
import ee.cone.c4gate.SessionAttr

trait SessionAttrAskFactory {
  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Access[P]]
  def askSessionAttrWithPK[P <: Product](attr: SessionAttr[P]): String => Dep[Access[P]]
  def askSessionAttrWithDefault[P <: Product](attr: SessionAttr[P], default: SrcId => P): Dep[Access[P]]
}

/**
 * Needs to be @provide if used
 */
trait CurrentTimeRequestFactory {
  def ask: Dep[Long]
  def byPkAsk: AskByPK[_ <: T_Time]
}

trait CurrentTimeAskFactory {
  def askCurrentTime(time: CurrentTime): CurrentTimeRequestFactory
}