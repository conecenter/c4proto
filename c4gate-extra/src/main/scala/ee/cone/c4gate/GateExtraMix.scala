package ee.cone.c4gate

import ee.cone.c4actor.{ComponentProvider, ComponentProviderApp, ComponentRegistry, RawSnapshotLoaderFactory, Signer, SnapshotLoaderFactory, SnapshotTask, SnapshotTaskSigner}
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

@deprecated trait FileRawSnapshotApp extends RemoteRawSnapshotApp with ComponentProviderApp { // Remote!
  lazy val snapshotTaskSigner: Signer[SnapshotTask] = resolveSingle(classOf[SnapshotTaskSigner])
  lazy val rawSnapshotLoaderFactory: RawSnapshotLoaderFactory = resolveSingle(classOf[RawSnapshotLoaderFactory])
  lazy val snapshotLoaderFactory: SnapshotLoaderFactory = resolveSingle(classOf[SnapshotLoaderFactory])
}

trait MetricFactoriesApp extends ComponentProviderApp with ComponentsApp {
  def metricFactories: List[MetricsFactory] = Nil
  private lazy val metricFactoriesComponent =
    provide(classOf[MetricsFactory],()=>metricFactories)
  override def components: List[Component] =
    metricFactoriesComponent :: super.components
}

trait SessionAttrApp extends SessionAttrCompApp with ComponentProviderApp {
  lazy val sessionAttrAccessFactory: SessionAttrAccessFactory = resolveSingle(classOf[SessionAttrAccessFactory])
}