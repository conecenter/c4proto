package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.{ContextId, MockRoleOpt, RoleId, UserId}
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.{N_ContextIdRequest, N_MockRoleRequest, N_RoleIdRequest, N_UserIdRequest}

import scala.collection.immutable.Map

trait CommonIdInjectApps
  extends ContextIdInjectApp
    with UserIdInjectApp
    with RoleIdInjectApp
with MockRoleIdInjectApp

trait ContextIdInjectApp extends DepAskFactoryApp {
  private lazy val sessionIdAsk: DepAsk[N_ContextIdRequest, ContextId] = depAskFactory.forClasses(classOf[N_ContextIdRequest], classOf[ContextId])

  def injectContext[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn => ContextId): DepHandler =
    sessionIdAsk.byParent(reason, (rq: ReasonIn) => Map(N_ContextIdRequest() -> handler(rq)))
}

trait UserIdInjectApp extends DepAskFactoryApp {
  private lazy val userIdAsk: DepAsk[N_UserIdRequest, ContextId] = depAskFactory.forClasses(classOf[N_UserIdRequest], classOf[UserId])

  def injectUser[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn => UserId): DepHandler =
    userIdAsk.byParent(reason, (rq: ReasonIn) => Map(N_UserIdRequest() -> handler(rq)))
}

trait RoleIdInjectApp extends DepAskFactoryApp {
  private lazy val roleIdAsk: DepAsk[N_RoleIdRequest, ContextId] = depAskFactory.forClasses(classOf[N_RoleIdRequest], classOf[RoleId])

  def injectRole[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn => RoleId): DepHandler =
    roleIdAsk.byParent(reason, (rq: ReasonIn) => Map(N_RoleIdRequest() -> handler(rq)))
}

trait MockRoleIdInjectApp extends DepAskFactoryApp {
  private lazy val mockRoleIdAsk: DepAsk[N_MockRoleRequest, MockRoleOpt] = depAskFactory.forClasses(classOf[N_MockRoleRequest], classOf[MockRoleOpt])

  def injectMockRole[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn => MockRoleOpt): DepHandler =
    mockRoleIdAsk.byParent(reason, (rq: ReasonIn) => Map(N_MockRoleRequest() -> handler(rq)))
}
