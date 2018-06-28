package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.{ContextId, RoleId, UserId}
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.{ContextIdRequest, RoleIdRequest, UserIdRequest}

import scala.collection.immutable.Map

trait CommonIdInjectApps
  extends ContextIdInjectApp
    with UserIdInjectApp
    with RoleIdInjectApp

trait ContextIdInjectApp extends DepAskFactoryApp {
  private lazy val sessionIdAsk: DepAsk[ContextIdRequest, ContextId] = depAskFactory.forClasses(classOf[ContextIdRequest], classOf[ContextId])

  def injectContext[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ ContextId): DepHandler =
    sessionIdAsk.byParent(reason, (rq: ReasonIn) ⇒ Map(ContextIdRequest() → handler(rq)))
}

trait UserIdInjectApp extends DepAskFactoryApp {
  private lazy val userIdAsk: DepAsk[UserIdRequest, ContextId] = depAskFactory.forClasses(classOf[UserIdRequest], classOf[UserId])

  def injectUser[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ UserId): DepHandler =
    userIdAsk.byParent(reason, (rq: ReasonIn) ⇒ Map(UserIdRequest() → handler(rq)))
}

trait RoleIdInjectApp extends DepAskFactoryApp {
  private lazy val roleIdAsk: DepAsk[RoleIdRequest, ContextId] = depAskFactory.forClasses(classOf[RoleIdRequest], classOf[RoleId])

  def injectRole[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ RoleId): DepHandler =
    roleIdAsk.byParent(reason, (rq: ReasonIn) ⇒ Map(RoleIdRequest() → handler(rq)))
}
