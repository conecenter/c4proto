package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.ContextId
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4actor.dep_impl.DepHandlersApp

object ContextTypes {
  type ContextId = String
}

trait CommonRequestUtilityFactory {
  def askByClassName[A](Class: Class[A], from: Int, to: Int): Dep[List[A]]

  def askContextId: Dep[ContextId]
}

case class CommonRequestUtilityFactoryImpl(
  byClassNameAsk: DepAsk[ByClassNameRequest, List[_]],
  contextAsk: DepAsk[ContextIdRequest, ContextId]
) extends CommonRequestUtilityFactory {
  def askByClassName[A](aCl: Class[A], from: Int = -1, to: Int = -1): Dep[List[A]] =
    byClassNameAsk.ask(ByClassNameRequest(aCl.getName, from, to)).map(_.asInstanceOf[List[A]])

  def askContextId: Dep[ContextId] =
    contextAsk.ask(ContextIdRequest())
}

trait CommonRequestUtilityApi {
  def commonRequestUtilityFactory: CommonRequestUtilityFactory
}

trait CommonRequestUtilityMix extends DepHandlersApp {
  def depAskFactory: DepAskFactory

  override def depHandlers: List[DepHandler] = super.depHandlers

  private def contextAsk: DepAsk[ContextIdRequest, ContextId] = depAskFactory.forClasses(classOf[ContextIdRequest], classOf[ContextId])

  private def byClassNameAsk: DepAsk[ByClassNameRequest, List[_]] = depAskFactory.forClasses(classOf[ByClassNameRequest], classOf[List[_]])

  lazy val commonRequestUtilityFactory: CommonRequestUtilityFactory =
    CommonRequestUtilityFactoryImpl(byClassNameAsk,
      contextAsk
    )
}