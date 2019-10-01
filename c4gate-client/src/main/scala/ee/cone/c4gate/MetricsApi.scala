package ee.cone.c4gate

import ee.cone.c4actor.Context

trait MetricFactoriesApp {
  def metricFactories: List[MetricsFactory] = Nil
}

case class MetricLabel(name: String, value: String)

case class Metric(name: String, labels: List[MetricLabel], value: Long)

object Metric {
  def apply(name: String, value: Long): Metric = new Metric(name, Nil, value)
}

/**
  * Trait for defining metrics
  */
trait MetricsFactory {
  def measure(local: Context): List[Metric]
}
