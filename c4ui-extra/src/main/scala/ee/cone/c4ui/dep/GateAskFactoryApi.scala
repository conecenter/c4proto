package ee.cone.c4ui.dep

import ee.cone.c4actor.Access
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.Dep
import ee.cone.c4gate.SessionAttr

trait SessionAttrAskFactoryApi {
  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]]
  def askSessionAttrWithPK[P <: Product](attr: SessionAttr[P]): String ⇒ Dep[Option[Access[P]]]
  def askSessionAttrWithDefault[P <: Product](attr: SessionAttr[P], default: SrcId ⇒ P): Dep[Option[Access[P]]]
}

trait CurrentTimeAskFactoryApi {
  def askCurrentTime(eachNSeconds: Long): Dep[Long]
}