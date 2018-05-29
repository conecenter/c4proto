package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.ContextId
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest

object ContextTypes {
  type ContextId = String
}

trait CommonRequestUtilityFactory {
  def askByClassName[A](Class: Class[A], from: Int, to: Int): Dep[List[A]]

  def askContextId: Dep[ContextId]
}

case class CommonRequestUtilityFactoryImpl(
  depAskFactory: DepAskFactory,
  contextAsk: DepAsk[ContextIdRequest,ContextId]
) extends CommonRequestUtilityFactory{
  def askByClassName[A](Class: Class[A], from: Int = -1, to: Int = -1): Dep[List[A]] =
    ???

  def askContextId: Dep[ContextId] =
    contextAsk.ask(ContextIdRequest())
}

trait CommonRequestUtilityApi {
  def commonRequestUtilityFactory: CommonRequestUtilityFactory
}

trait CommonRequestUtilityMix {
  def depAskFactory: DepAskFactory

  lazy val commonRequestUtilityFactory: CommonRequestUtilityFactory =
    CommonRequestUtilityFactoryImpl(depAskFactory,
      depAskFactory.forClasses(classOf[ContextIdRequest],classOf[ContextId])
    )
}