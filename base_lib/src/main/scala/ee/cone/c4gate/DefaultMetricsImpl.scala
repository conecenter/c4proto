package ee.cone.c4gate

import ee.cone.c4actor.Context
import ee.cone.c4assemble.{IndexUtil, JoinKey, ReadModelUtil}
import ee.cone.c4assemble.Types.Index
import ee.cone.c4di.c4

@c4("DefaultMetricsApp") final class RichWorldMetricsFactory(readModelUtil: ReadModelUtil, indexUtil: IndexUtil) extends MetricsFactory {
  def measure(local: Context): List[Metric] =
    readModelUtil.toMap(local.assembled).toList.collect{
      case (worldKey: JoinKey, index: Index) if !worldKey.was && worldKey.keyAlias == "SrcId" =>
        (worldKey.valueClassName,index)
    }.sortBy(_._1).map {
      case (key, index) =>
        Metric("c4index_key_count", MetricLabel("valClass", key) :: Nil, indexUtil.size(index).toLong)
    }
}

@c4("DefaultMetricsApp") final class RuntimeMemoryMetricsFactory extends MetricsFactory {
  def measure(local: Context): List[Metric] = {
    val runtime = Runtime.getRuntime
    List(
      //sk: seems to be: max > total > free
      Metric("runtime_mem_max", runtime.maxMemory),
      Metric("runtime_mem_total", runtime.totalMemory),
      Metric("runtime_mem_free", runtime.freeMemory),
      Metric("gate_api_version", 1L),
    )
  }
}
