package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{AssembleStatsAccumulator, Config, Context, Early, Executable}
import ee.cone.c4assemble.{IndexUtil, JoinKey, ReadModelUtil}
import ee.cone.c4assemble.Types.Index
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.util.Try

@c4("DefaultMetricsApp") final class RichWorldMetricsFactory(readModelUtil: ReadModelUtil, indexUtil: IndexUtil) extends MetricsFactory {
  def measure(local: Context): List[Metric] =
    readModelUtil.toMap(local.assembled).toList.collect{
      case (worldKey: JoinKey, index: Index) if !worldKey.was && worldKey.keyAlias == "SrcId" =>
        (worldKey.valueClassName,index)
    }.sortBy(_._1).map {
      case (key, index) =>
        Metric("c4index_key_count", MetricLabel("valClass", key) :: Nil, indexUtil.keyCount(index).toLong)
    }
}

@c4("DefaultMetricsApp") final class PerReplicaMetrics(
  assembleStatsAccumulator: AssembleStatsAccumulator, util: HttpUtil, settings: PrometheusPostSettings, config: Config
)(
  url: String = {
    val parts = settings.url.split('/')
    val hostname = config.get("HOSTNAME")
    assert(hostname.startsWith(parts.last))
    (parts.dropRight(1).toSeq ++ Seq(hostname)).mkString("/")
  }
) extends Executable with Early with LazyLogging {
  def run(): Unit = iter()
  @tailrec private def iter(): Unit = {
    Try(tryIter())
    Thread.sleep(settings.refreshRate)
    iter()
  }
  private def tryIter(): Unit = {
    val runtime = Runtime.getRuntime
    val metrics = assembleStatsAccumulator.report().map {
      case (k, 0, v) => Metric(k, MetricLabel("stage", "read") :: Nil, v)
      case (k, 1, v) => Metric(k, MetricLabel("stage", "add") :: Nil, v)
    } ::: List(
      //sk: seems to be: max > total > free
      Metric("runtime_mem_max", runtime.maxMemory),
      Metric("runtime_mem_total", runtime.totalMemory),
      Metric("runtime_mem_free", runtime.freeMemory),
      Metric("gate_api_version", 1L),
    )
    val bodyStr = PrometheusMetricBuilder(metrics)
    val bodyBytes = ToByteString(bodyStr.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Posting ${metrics.size} metrics to $url")
    util.post(url, Nil, bodyBytes, Option(5000), expectCode = 200, 202)
  }
}
