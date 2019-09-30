package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.dep._
import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble

import scala.collection.immutable.Seq

trait DepHandlersApp {
  def depHandlers: List[DepHandler] = Nil
}

trait DepResponseFiltersApp {
  def depFilters: List[DepResponseForwardFilter] = Nil
}

trait DepAssembleApp extends AssemblesApp {
  def preHashing: PreHashing
  def idGenUtil: IdGenUtil
  def qAdapterRegistry: QAdapterRegistry
  def depHandlers: Seq[DepHandler]
  def depFilters: Seq[DepResponseForwardFilter]
  //
  lazy val depFactory: DepFactory = DepFactoryImpl()
  lazy val depAskFactory: DepAskFactory = DepAskFactoryImpl(depFactory)
  lazy val depResponseFactory: DepResponseFactory = DepResponseFactoryImpl()(preHashing)
  lazy val depRequestFactory: DepRequestFactory = DepRequestFactoryImpl(idGenUtil)(qAdapterRegistry)
  private lazy val requestHandlerRegistry =
    DepRequestHandlerRegistry(depRequestFactory,depResponseFactory,depHandlers, depFilters)()
  override def assembles: List[Assemble] =
    new DepAssemble(requestHandlerRegistry) :: super.assembles
}

///

trait AskByPKsApp {
  def askByPKs: List[AbstractAskByPK] = Nil
}

trait ByPKRequestHandlerApp extends ByPKRequestHandlerAutoApp with AssemblesApp {
  def askByPKs: List[AbstractAskByPK]
  def depResponseFactory: DepResponseFactory
  def depAskFactory: DepAskFactory

  lazy val askByPKFactory: AskByPKFactory = AskByPKFactoryImpl(depAskFactory,depResponseFactory)
  override def assembles: List[Assemble] = ByPKAssembles(askByPKs) ::: super.assembles
}
