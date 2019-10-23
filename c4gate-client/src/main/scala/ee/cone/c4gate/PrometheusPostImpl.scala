package ee.cone.c4gate

import java.nio.charset.StandardCharsets
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Config, Context, SleepUntilKey, TxTransform, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, byEq, c4assemble}
import ee.cone.c4gate.PrometheusPostSettingsObj.PrometheusPushId
import ee.cone.c4proto.{ToByteString, c4, provide}

import scala.util.Try

@c4("PrometheusPostApp") class PrometheusPostSettingsProvider(config: Config) {
  def defaultPrometheusPostRefresh: Long = 30L * 1000L
  @provide def get: Seq[PrometheusPostSettings] =
    Try(config.get("C4PROMETHEUS_POST_URL")).toOption.toList
      .map(url => PrometheusPostSettings(url, defaultPrometheusPostRefresh))
}

object PrometheusMetricBuilder {
  def apply(metrics: List[Metric]): String =
    metrics.map(metricToString(_, "")).mkString("\n", "\n", "\n")

  //def withTimeStamp(metrics: List[Metric], time: Long): String =
  //  metrics.map(metricToString(_, time.toString)).mkString("\n", "\n", "\n")

  def metricToString(metric: Metric, extraInfo: String): String =
    s"${metric.name}${metric.labels.map(label => s"""${label.name}="${label.value}"""").mkString("{", ",", "}")} ${metric.value}${extraInfo.trim match { case "" => "" case a => s" $a"}}"
}

case class PrometheusPostSettings(url: String, refreshRate: Long)

object PrometheusPostSettingsObj {
  type PrometheusPushId = SrcId
  lazy val fixedSrcId: PrometheusPushId = "prometheus-post-tx"
}

import PrometheusPostSettingsObj._

@c4assemble("PrometheusPostApp") class PrometheusPostAssembleBase(metricsFactories: List[MetricsFactory], defaultSettings: List[PrometheusPostSettings], util: HttpUtil) {
  def joinStub(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(PrometheusPushId, PrometheusPostSettings)] =
    Nil

  def createPrometheusPost(
    key: SrcId,
    first: Each[S_Firstborn],
    @byEq[PrometheusPushId](fixedSrcId) settings: Values[PrometheusPostSettings]
  ): Values[(SrcId, TxTransform)] =
    Single.option(settings).orElse(Single.option(defaultSettings)).toList.map { settings =>
      WithPK(PrometheusPostTx(fixedSrcId, settings)(metricsFactories, util))
    }
}

case class PrometheusPostTx(srcId: SrcId, settings: PrometheusPostSettings)(metricsFactories: List[MetricsFactory], util: HttpUtil) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val time = System.currentTimeMillis
    val metrics = metricsFactories.flatMap(_.measure(local))
    val bodyStr = PrometheusMetricBuilder(metrics)
    val bodyBytes = ToByteString(bodyStr.getBytes(StandardCharsets.UTF_8))
    logger.debug(s"Posted ${metrics.size} metrics to ${settings.url}")
    // mimeTypeOpt.map(mimeType => ("content-type", mimeType)).toList
    util.post(settings.url, Nil, bodyBytes, Option(5000), expectCode = 202)
    SleepUntilKey.set(Instant.ofEpochMilli(time + settings.refreshRate))(local)
  }
}
