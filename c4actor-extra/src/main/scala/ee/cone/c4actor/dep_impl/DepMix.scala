package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.dep._
import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.{Component, ComponentsApp}

import scala.collection.immutable.Seq

import ComponentProvider.provide

trait DepHandlersApp extends ComponentsApp {
  def depHandlers: List[DepHandler] = Nil
  private lazy val depHandlersComponent = provide(classOf[DepHandler],Nil,()=>depHandlers)
  override def components: List[Component] = depHandlersComponent :: super.components
}

trait DepResponseFiltersApp extends ComponentsApp {
  def depFilters: List[DepResponseForwardFilter] = Nil
  private lazy val depFiltersComponent = provide(classOf[DepResponseForwardFilter],Nil,()=>depFilters)
  override def components: List[Component] = depFiltersComponent :: super.components
}

trait DepAssembleApp extends DepAssembleCompApp {
  import ComponentProvider._
  implicit def componentRegistry: ComponentRegistry
  //
  lazy val depFactory: DepFactory = resolveSingle(classOf[DepFactory])
  lazy val depAskFactory: DepAskFactory = resolveSingle(classOf[DepAskFactory])
  lazy val depResponseFactory: DepResponseFactory = resolveSingle(classOf[DepResponseFactory])
  lazy val depRequestFactory: DepRequestFactory = resolveSingle(classOf[DepRequestFactory])
}

///

trait AskByPKsApp {
  def askByPKs: List[AbstractAskByPK] = Nil
}

trait ByPKRequestHandlerApp extends ByPKRequestHandlerCompApp with AssemblesApp {
  def askByPKs: List[AbstractAskByPK]
  def depResponseFactory: DepResponseFactory
  def depAskFactory: DepAskFactory

  lazy val askByPKFactory: AskByPKFactory = AskByPKFactoryImpl(depAskFactory,depResponseFactory)
  override def assembles: List[Assemble] = ByPKAssembles(askByPKs) ::: super.assembles
}
