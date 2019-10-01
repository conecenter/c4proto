package ee.cone.c4gate

import java.nio.charset.StandardCharsets
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Context, HttpUtil, SleepUntilKey, TxTransform, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, assemble, byEq}
import ee.cone.c4gate.PrometheusPostSettingsObj.PrometheusPushId
import ee.cone.c4proto.ToByteString

object PrometheusMetricBuilder {
  def apply(metrics: List[Metric]): String =
    metrics.map(metricToString(_, "")).mkString("\n", "\n", "\n")

  def withTimeStamp(metrics: List[Metric], time: Long): String =
    metrics.map(metricToString(_, time.toString)).mkString("\n", "\n", "\n")

  def metricToString(metric: Metric, extraInfo: String): String =
    s"${metric.name}${metric.labels.map(label ⇒ s"""${label.name}="${label.value}"""").mkString("{", ",", "}")} ${metric.value}${if (extraInfo.isBlank) "" else s" $extraInfo"}"
}

case class PrometheusPostSettings(url: String, refreshRate: Long)

object PrometheusPostSettingsObj {
  type PrometheusPushId = SrcId
  lazy val fixedSrcId: PrometheusPushId = "prometheus-post-tx"
}

import PrometheusPostSettingsObj._

@assemble class PrometheusPostAssembleBase(metricsFactories: List[MetricsFactory], defaultSettings: Option[PrometheusPostSettings]) {
  def StubJoin(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(PrometheusPushId, PrometheusPostSettings)] =
    Nil

  def CreatePrometheusPost(
    key: SrcId,
    first: Each[S_Firstborn],
    @byEq[PrometheusPushId](fixedSrcId) settings: Values[PrometheusPostSettings]
  ): Values[(SrcId, TxTransform)] =
    Single.option(settings).orElse(defaultSettings).toList.map { settings ⇒
      WithPK(PrometheusPostTx(fixedSrcId, settings, metricsFactories))
    }
}

case class PrometheusPostTx(srcId: SrcId, settings: PrometheusPostSettings, metricsFactories: List[MetricsFactory]) extends TxTransform with LazyLogging {

  def transform(local: Context): Context = {
    val time = System.currentTimeMillis
    val metrics = metricsFactories.flatMap(_.measure(local))
    val bodyStr = PrometheusMetricBuilder(metrics)
    val bodyBytes = ToByteString(bodyStr.getBytes(StandardCharsets.UTF_8))
    logger.debug(s"Posted ${metrics.size} metrics to ${settings.url}")
    HttpUtil.post(settings.url, bodyBytes, None, Nil, 5000, expectCode = 202)
    SleepUntilKey.set(Instant.ofEpochMilli(time + settings.refreshRate))(local)
  }
}
