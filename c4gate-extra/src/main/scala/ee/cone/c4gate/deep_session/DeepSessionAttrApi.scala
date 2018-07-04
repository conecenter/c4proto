package ee.cone.c4gate.deep_session

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Access, Context, MetaAttr, TransientLens}
import ee.cone.c4gate.{SessionAttr, SessionAttrAccessFactory}

trait DeepSessionAttrAccessFactory extends SessionAttrAccessFactory{
  def toUser[P <: Product](attr: SessionAttr[P]): Context ⇒ Option[Access[P]]

  def toRole[P <: Product](attr: SessionAttr[P]): Context ⇒ Option[Access[P]]
}

case object CurrentUserIdKey extends TransientLens[SrcId]("")

case object CurrentRoleIdKey extends TransientLens[SrcId]("")

case object UserLevelAttr extends MetaAttr