package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.{ContextId, MockRoleOpt, RoleId, UserId}
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.{ContextIdRequest, MockRoleRequest, RoleIdRequest, UserIdRequest}
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
  byClassNameAsk: DepAsk[ByClassNameRequest, List[_]],
  contextAsk: DepAsk[ContextIdRequest, ContextId],
  userAsk: DepAsk[UserIdRequest, ContextId],
  roleAsk: DepAsk[RoleIdRequest, ContextId],
  mockRoleAsk: DepAsk[MockRoleRequest, MockRoleOpt]
) extends CommonRequestUtilityFactory {
  def askByClassName[A](aCl: Class[A], from: Int = -1, to: Int = -1): Dep[List[A]] =
    byClassNameAsk.ask(ByClassNameRequest(aCl.getName, from, to)).map(_.asInstanceOf[List[A]])

  def askContextId: Dep[ContextId] =
    contextAsk.ask(ContextIdRequest())

  def askUserId: Dep[UserId] =
    userAsk.ask(UserIdRequest())

  def askRoleId: Dep[RoleId] =
    roleAsk.ask(RoleIdRequest())

  def askMockRole: Dep[MockRoleOpt] =
    mockRoleAsk.ask(MockRoleRequest())

}

trait CommonRequestUtilityApi {
  def commonRequestUtilityFactory: CommonRequestUtilityFactory
}

trait CommonRequestUtilityMix extends DepHandlersApp with DepFactoryApp {
  def depAskFactory: DepAskFactory

  override def depHandlers: List[DepHandler] = super.depHandlers

  private lazy val contextAsk: DepAsk[ContextIdRequest, ContextId] = depAskFactory.forClasses(classOf[ContextIdRequest], classOf[ContextId])

  private lazy val userAsk: DepAsk[UserIdRequest, UserId] = depAskFactory.forClasses(classOf[UserIdRequest], classOf[UserId])

  private lazy val roleAsk: DepAsk[RoleIdRequest, RoleId] = depAskFactory.forClasses(classOf[RoleIdRequest], classOf[RoleId])

  private lazy val byClassNameAsk: DepAsk[ByClassNameRequest, List[_]] = depAskFactory.forClasses(classOf[ByClassNameRequest], classOf[List[_]])

  private lazy val mockRoleAsk: DepAsk[MockRoleRequest, MockRoleOpt] = depAskFactory.forClasses(classOf[MockRoleRequest], classOf[MockRoleOpt])

  lazy val commonRequestUtilityFactory: CommonRequestUtilityFactory =
    CommonRequestUtilityFactoryImpl(depFactory, byClassNameAsk,
      contextAsk, userAsk, roleAsk, mockRoleAsk
    )
}