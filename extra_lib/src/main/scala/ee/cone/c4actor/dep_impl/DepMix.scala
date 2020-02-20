package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.dep._
import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4di.{Component, ComponentsApp}

import ee.cone.c4actor.ComponentProvider.provide

trait DepHandlersApp extends ComponentsApp {
  def depHandlers: List[DepHandler] = Nil
  private lazy val depHandlersComponent = provide(classOf[DepHandler], () => depHandlers)
  override def components: List[Component] = depHandlersComponent :: super.components
}

trait DepResponseFiltersApp extends ComponentsApp {
  def depFilters: List[DepResponseForwardFilter] = Nil
  private lazy val depFiltersComponent = provide(classOf[DepResponseForwardFilter], () => depFilters)
  override def components: List[Component] = depFiltersComponent :: super.components
}

trait DepAssembleApp extends DepAssembleCompApp with ComponentProviderApp {
  lazy val depFactory: DepFactory = resolveSingle(classOf[DepFactory])
  lazy val depAskFactory: DepAskFactory = resolveSingle(classOf[DepAskFactory])
  lazy val depResponseFactory: DepResponseFactory = resolveSingle(classOf[DepResponseFactory])
  lazy val depRequestFactory: DepRequestFactory = resolveSingle(classOf[DepRequestFactory])
}

///

trait AskByPKsApp {
  def askByPKs: List[AbstractAskByPK] = Nil
}

trait ByPKRequestHandlerApp extends ByPKRequestHandlerCompApp with AssemblesApp with AskByPKsApp with ComponentsApp with ComponentProviderApp {
  lazy val askByPKFactory: AskByPKFactory = resolveSingle(classOf[AskByPKFactory])
  private lazy val askByPKsComponent = provide(classOf[AbstractAskByPK], () => askByPKs)
  override def components: List[Component] = askByPKsComponent :: super.components
}
