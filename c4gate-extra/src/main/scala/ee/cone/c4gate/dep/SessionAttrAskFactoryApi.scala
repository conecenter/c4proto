package ee.cone.c4gate.dep

import ee.cone.c4actor.Access
import ee.cone.c4actor.dep.Dep
import ee.cone.c4gate.SessionAttr

trait SessionAttrAskFactoryApi {
  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]]
}
