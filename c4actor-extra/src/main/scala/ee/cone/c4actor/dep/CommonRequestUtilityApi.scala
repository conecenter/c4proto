package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.{ContextId, MockRoleOpt, RoleId, UserId}
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.N_ByClassNameRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.{N_ContextIdRequest, N_MockRoleRequest, N_RoleIdRequest, N_UserIdRequest}
import ee.cone.c4actor.dep_impl.DepHandlersApp

object ContextTypes {
  type ContextId = String
  type UserId = String
  type RoleId = String
  type MockRoleOpt = Option[(RoleId, Boolean)] // Id and if editable
}

trait CommonRequestUtilityFactory {
  def askByClassName[A](Class: Class[A], from: Int, to: Int): Dep[List[A]]

  def askContextId: Dep[ContextId]

  def askUserId: Dep[UserId]

  def askRoleId: Dep[RoleId]

  def askMockRole: Dep[MockRoleOpt]
}

case class CommonRequestUtilityFactoryImpl(
  depFactory: DepFactory,
  byClassNameAsk: DepAsk[N_ByClassNameRequest, List[_]],
  contextAsk: DepAsk[N_ContextIdRequest, ContextId],
  userAsk: DepAsk[N_UserIdRequest, ContextId],
  roleAsk: DepAsk[N_RoleIdRequest, ContextId],
  mockRoleAsk: DepAsk[N_MockRoleRequest, MockRoleOpt]
) extends CommonRequestUtilityFactory {
  def askByClassName[A](aCl: Class[A], from: Int = -1, to: Int = -1): Dep[List[A]] =
    byClassNameAsk.ask(N_ByClassNameRequest(aCl.getName, from, to)).map(_.asInstanceOf[List[A]])

  def askContextId: Dep[ContextId] =
    contextAsk.ask(N_ContextIdRequest())

  def askUserId: Dep[UserId] =
    userAsk.ask(N_UserIdRequest())

  def askRoleId: Dep[RoleId] =
    roleAsk.ask(N_RoleIdRequest())

  def askMockRole: Dep[MockRoleOpt] =
    mockRoleAsk.ask(N_MockRoleRequest())

}

trait CommonRequestUtilityApi {
  def commonRequestUtilityFactory: CommonRequestUtilityFactory
}

trait CommonRequestUtilityMix extends DepHandlersApp with DepFactoryApp {
  def depAskFactory: DepAskFactory

  override def depHandlers: List[DepHandler] = super.depHandlers

  private lazy val contextAsk: DepAsk[N_ContextIdRequest, ContextId] = depAskFactory.forClasses(classOf[N_ContextIdRequest], classOf[ContextId])

  private lazy val userAsk: DepAsk[N_UserIdRequest, UserId] = depAskFactory.forClasses(classOf[N_UserIdRequest], classOf[UserId])

  private lazy val roleAsk: DepAsk[N_RoleIdRequest, RoleId] = depAskFactory.forClasses(classOf[N_RoleIdRequest], classOf[RoleId])

  private lazy val byClassNameAsk: DepAsk[N_ByClassNameRequest, List[_]] = depAskFactory.forClasses(classOf[N_ByClassNameRequest], classOf[List[_]])

  private lazy val mockRoleAsk: DepAsk[N_MockRoleRequest, MockRoleOpt] = depAskFactory.forClasses(classOf[N_MockRoleRequest], classOf[MockRoleOpt])

  lazy val commonRequestUtilityFactory: CommonRequestUtilityFactory =
    CommonRequestUtilityFactoryImpl(depFactory, byClassNameAsk,
      contextAsk, userAsk, roleAsk, mockRoleAsk
    )
}