package ee.cone.c4gate

import ee.cone.c4actor.{ComponentProvider, ComponentRegistry}
import ee.cone.c4gate.{HttpProtocolApp, PublishFromStringsProvider, PublishMimeTypesProvider}
import ee.cone.c4proto.{Component, ComponentsApp}
import ComponentProvider.provide


trait PublishingApp extends PublishingCompApp with ComponentsApp {
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]
  //
  private lazy val mimeTypesComponent = provide(classOf[PublishMimeTypesProvider],()=>List(
    new PublishMimeTypesProvider {
      def get: List[(String, String)] = mimeTypes.toList
    }
  ))
  private lazy val publishFromStringsComponent = provide(classOf[PublishFromStringsProvider],()=>List(
    new PublishFromStringsProvider {
      def get: List[(String, String)] = publishFromStrings
    }
  ))
  override def components: List[Component] =
    mimeTypesComponent :: publishFromStringsComponent :: super.components
}

@deprecated trait FileRawSnapshotApp extends RemoteRawSnapshotApp  // Remote!