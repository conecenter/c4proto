package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, Config}
import ee.cone.c4assemble.Assemble

import scala.util.Try

trait PrometheusPostApp extends AssemblesApp with DefaultMetricsApp {
  def config: Config

  lazy val defaultPrometheusPostRefresh: Long = 30L * 1000L
  lazy val defaultPrometheusPostUrl: Option[String] = Try(config.get("C4PROMETHEUS_POST_URL")).toOption

  override def assembles: List[Assemble] = new PrometheusPostAssemble(metricFactories, defaultPrometheusPostUrl.map(url ⇒ PrometheusPostSettings(url, defaultPrometheusPostRefresh))) :: super.assembles
}
