package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Index, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.ActorAccessProtocol.C_ActorAccessKey
import ee.cone.c4gate.AvailabilitySettingProtocol.C_AvailabilitySetting
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPublication}
import ee.cone.c4proto.{Id, Protocol, protocol}
import okio.ByteString

@protocol object ActorAccessProtocolBase   {
  @Id(0x006A) case class C_ActorAccessKey(
    @Id(0x006B) srcId: String,
    @Id(0x006C) value: String
  )
}

@assemble class ActorAccessAssembleBase   {
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    accessKeys: Values[C_ActorAccessKey]
  ): Values[(SrcId,TxTransform)] =
    if(accessKeys.nonEmpty) Nil
    else List(WithPK(ActorAccessCreateTx(s"ActorAccessCreateTx-${first.srcId}",first)))
}

case class ActorAccessCreateTx(srcId: SrcId, first: S_Firstborn) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(LEvent.update(C_ActorAccessKey(first.srcId,s"${UUID.randomUUID}")))(local)
}

@assemble class PrometheusAssembleBase(compressor: Compressor, metricsFactories: List[MetricsFactory])   {
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    accessKey: Each[C_ActorAccessKey]
  ): Values[(SrcId,TxTransform)] = {
    val path = s"/${accessKey.value}-metrics"
    println(s"Prometheus metrics at $path")
    List(WithPK(PrometheusTx(path, compressor, metricsFactories)))
  }
}

object PrometheusMetricBuilder {
  def withTimeStamp(metrics: List[Metric], time: Long): String =
    metrics.map(metricToString(_, time)).mkString("\n")

  def metricToString(metric: Metric, timeStamp: Long): String =
    s"${metric.name}${metric.labels.map(label ⇒ s"""${label.name}="${label.value}""").mkString("{",",","}")} ${metric.value} $timeStamp"
}

case class PrometheusTx(path: String, compressor: Compressor, metricsFactories: List[MetricsFactory]) extends TxTransform {

  def transform(local: Context): Context = {
    val time = System.currentTimeMillis
    val metrics = metricsFactories.flatMap(_.measure(local))
    val bodyStr = PrometheusMetricBuilder.withTimeStamp(metrics, time)
    val body = compressor.compress(okio.ByteString.encodeUtf8(bodyStr))
    val headers = List(N_Header("content-encoding", compressor.name))
    Monitoring.publish(time, 15000, 5000, path, headers, body)(local)
  }
}

object Monitoring {
  def publish(
    time: Long, updatePeriod: Long, timeout: Long,
    path: String, headers: List[N_Header], body: okio.ByteString
  ): Context⇒Context = {
    val nextTime = time + updatePeriod
    val invalidateTime = nextTime + timeout
    val publication = S_HttpPublication(path, headers, body, Option(invalidateTime))
    TxAdd(LEvent.update(publication)).andThen(SleepUntilKey.set(Instant.ofEpochMilli(nextTime)))
  }
}

@assemble class AvailabilityAssembleBase(updateDef: Long, timeoutDef: Long)   {
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    settings: Values[C_AvailabilitySetting]
  ): Values[(SrcId,TxTransform)] = {
    val (updatePeriod, timeout) = Single.option(settings.map(s ⇒ s.updatePeriod → s.timeout)).getOrElse((updateDef, timeoutDef))
    List(WithPK(AvailabilityTx(s"AvailabilityTx-${first.srcId}", updatePeriod, timeout)))
  }
}

@protocol object AvailabilitySettingProtocolBase  {

  @Id(0x00f0) case class C_AvailabilitySetting(
    @Id(0x0001) srcId: String,
    @Id(0x0002) updatePeriod: Long,
    @Id(0x0003) timeout: Long
  )

}

case class AvailabilityTx(srcId: SrcId, updatePeriod: Long, timeout: Long) extends TxTransform {
  def transform(local: Context): Context =
    Monitoring.publish(
      System.currentTimeMillis, updatePeriod, timeout,
      "/availability", Nil, ByteString.EMPTY
    )(local)
}