package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Component, ComponentsApp}

trait DefaultModelRegistry {
  def get[P<:Product](className: String): DefaultModelFactory[P]
}

abstract class DefaultModelFactory[P](val valueClass: Class[P], val create: SrcId=>P)

trait DefaultModelFactoriesApp extends ComponentsApp {
  private lazy val defaultModelFactoriesComponent = ComponentRegistry.provide(classOf[DefaultModelFactory[_]], Nil, ()=>defaultModelFactories)
  override def components: List[Component] = defaultModelFactoriesComponent :: super.components
  def defaultModelFactories: List[DefaultModelFactory[_]] = Nil
}