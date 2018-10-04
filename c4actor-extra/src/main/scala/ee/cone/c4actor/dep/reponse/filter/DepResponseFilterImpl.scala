package ee.cone.c4actor.dep.reponse.filter

import ee.cone.c4actor.dep.{DepResponse, DepResponseFilterFactory, DepResponseForwardFilter}
import ee.cone.c4actor.dep.DepTypes.DepRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.{ContextIdRequest, MockRoleRequest, RoleIdRequest, UserIdRequest}
import ee.cone.c4actor.dep_impl.DepResponseFiltersApp

case class DepResponseForwardFilterImpl(parentCl: Option[Class[_ <: DepRequest]], childCl: Class[_ <: DepRequest])(val filter: DepResponse ⇒ Option[DepResponse]) extends DepResponseForwardFilter

case class DepResponseFilterFactoryImpl() extends DepResponseFilterFactory {
  def withParent(parentCl: Class[_ <: DepRequest], childCl: Class[_ <: DepRequest]): (DepResponse => Option[DepResponse]) => DepResponseForwardFilter =
    DepResponseForwardFilterImpl(Some(parentCl), childCl)

  def withChild(childCl: Class[_ <: DepRequest]): (DepResponse => Option[DepResponse]) => DepResponseForwardFilter =
    DepResponseForwardFilterImpl(None, childCl)
}

trait DepResponseFilterFactoryApp {
  def depResponseFilterFactory: DepResponseFilterFactory
}

trait DepResponseFilterFactoryMix extends DepResponseFilterFactoryApp {
  def depResponseFilterFactory: DepResponseFilterFactory = DepResponseFilterFactoryImpl()
}

trait DepCommonResponseForward {
  def forwardSessionIds(request: Class[_ <: DepRequest]): DepResponseForwardFilter
}

case class DepCommonResponseForwardImpl(factory: DepResponseFilterFactory) extends DepCommonResponseForward {
  def forwardSessionIds(request: Class[_ <: DepRequest]): DepResponseForwardFilter = {
    factory.withChild(request)(resp ⇒ resp.innerRequest.request match {
      case _: ContextIdRequest | _: RoleIdRequest | _: UserIdRequest | _: MockRoleRequest ⇒ Some(resp)
      case _ ⇒ None
    }
    )
  }
}

trait DepCommonResponseForwardApp {
  def depCommonResponseForward: DepCommonResponseForward
}

trait DepCommonResponseForwardMix extends DepCommonResponseForwardApp with DepResponseFilterFactoryApp {
  def depCommonResponseForward: DepCommonResponseForward = DepCommonResponseForwardImpl(depResponseFilterFactory)
}

trait DepForwardUserAttributesApp {
  def childRequests: List[Class[_ <: Product ]] = Nil
}

trait DepForwardUserAttributesMix extends DepForwardUserAttributesApp with  DepCommonResponseForwardApp with DepResponseFiltersApp{
  override def depFilters: List[DepResponseForwardFilter] =  childRequests.map(depCommonResponseForward.forwardSessionIds) ::: super.depFilters
}
