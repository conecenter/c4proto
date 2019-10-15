package ee.cone.c4gate

import ee.cone.c4actor.Context
import ee.cone.c4assemble.{IndexUtil, JoinKey, ReadModelUtil}
import ee.cone.c4assemble.Types.Index
import ee.cone.c4proto.c4component

// TODO rewrite when ReadModelUtil and IndexUtil are components
trait DefaultMetricsApp extends MetricFactoriesApp {
  def readModelUtil: ReadModelUtil
  def indexUtil: IndexUtil

  override def metricFactories: List[MetricsFactory] =
    new RichWorldMetricsFactory(readModelUtil, indexUtil) ::
      new RuntimeMemoryMetricsFactory() :: super.metricFactories
}

@c4component("DefaultMetricsComponentsApp") class RichWorldMetricsFactory(readModelUtil: ReadModelUtil, indexUtil: IndexUtil) extends MetricsFactory {
  def measure(local: Context): List[Metric] =
    readModelUtil.toMap(local.assembled).collect {
      case (worldKey: JoinKey, index: Index)
        if !worldKey.was && worldKey.keyAlias == "SrcId" ⇒
        Metric("c4index_key_count", MetricLabel("valClass", worldKey.valueClassName) :: Nil, indexUtil.keySet(index).size.toLong)
    }.toList
}

@c4component("DefaultMetricsComponentsApp") class RuntimeMemoryMetricsFactory extends MetricsFactory {
  def measure(local: Context): List[Metric] = {
    val runtime = Runtime.getRuntime
    List(
      //sk: seems to be: max > total > free
      Metric("runtime_mem_max", runtime.maxMemory),
      Metric("runtime_mem_total", runtime.totalMemory),
      Metric("runtime_mem_free", runtime.freeMemory)
    )
  }
}
