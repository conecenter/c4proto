package ee.cone.c4gate.deep_session

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Access, Context, AbstractMetaAttr, TransientLens}
import ee.cone.c4gate.{SessionAttr, SessionAttrAccessFactory}

trait DeepSessionAttrAccessFactory extends SessionAttrAccessFactory{
  def toUser[P <: Product](attr: SessionAttr[P]): Context => Access[P]

  def toRole[P <: Product](attr: SessionAttr[P]): Context => Access[P]
}

case object CurrentUserIdKey extends TransientLens[SrcId]("")

case object CurrentRoleIdKey extends TransientLens[SrcId]("")

case object UserLevelAttr extends AbstractMetaAttr

case object RoleLevelAttr extends AbstractMetaAttr