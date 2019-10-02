package ee.cone.c4gate

import ee.cone.c4actor.ComponentRegistry
import ee.cone.c4gate.{HttpProtocolApp, PublishFromStringsProvider, PublishMimeTypesProvider}
import ee.cone.c4proto.{Component, ComponentsApp}

trait PublishingApp extends PublishingCompApp with ComponentsApp {
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]
  //
  private lazy val mimeTypesComponent = ComponentRegistry.provide(classOf[PublishMimeTypesProvider],Nil,()=>List(
    new PublishMimeTypesProvider {
      def get: List[(String, String)] = mimeTypes.toList
    }
  ))
  private lazy val publishFromStringsComponent = ComponentRegistry.provide(classOf[PublishFromStringsProvider],Nil,()=>List(
    new PublishFromStringsProvider {
      def get: List[(String, String)] = publishFromStrings
    }
  ))
  override def components: List[Component] =
    mimeTypesComponent :: publishFromStringsComponent :: super.components
}