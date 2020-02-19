package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.c4
import ee.cone.c4gate.ActorAccessProtocol.C_ActorAccessKey
import ee.cone.c4gate.AvailabilitySettingProtocol.C_AvailabilitySetting
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4proto.{Id, protocol}
import okio.ByteString

@protocol("ActorAccessApp") object ActorAccessProtocol {

  @Id(0x006A) case class C_ActorAccessKey(
    @Id(0x006B) srcId: String,
    @Id(0x006C) value: String
  )

}

@c4assemble("ActorAccessApp") class ActorAccessAssembleBase {
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    accessKeys: Values[C_ActorAccessKey]
  ): Values[(SrcId, TxTransform)] =
    if (accessKeys.nonEmpty) Nil
    else List(WithPK(ActorAccessCreateTx(s"ActorAccessCreateTx-${first.srcId}", first)))
}

case class ActorAccessCreateTx(srcId: SrcId, first: S_Firstborn) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(LEvent.update(C_ActorAccessKey(first.srcId, s"${UUID.randomUUID}")))(local)
}

/*
@c4assemble("PrometheusApp") class PrometheusAssembleBase(compressor: PublishFullCompressor, metricsFactories: List[MetricsFactory])   {
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    accessKey: Each[C_ActorAccessKey]
  ): Values[(SrcId,TxTransform)] = {
    val path = s"/${accessKey.value}-metrics"
    println(s"Prometheus metrics at $path")
    List(WithPK(PrometheusTx(path)(compressor.value, metricsFactories)))
  }
}

case class PrometheusTx(path: String)(compressor: Compressor, metricsFactories: List[MetricsFactory]) extends TxTransform {

  def transform(local: Context): Context = {
    val time = System.currentTimeMillis
    val metrics = metricsFactories.flatMap(_.measure(local))
    val bodyStr = PrometheusMetricBuilder.withTimeStamp(metrics, time)
    val body = compressor.compress(okio.ByteString.encodeUtf8(bodyStr))
    val headers = List(N_Header("content-encoding", compressor.name))
    Monitoring.publish(time, 15000, 5000, path, headers, body)(local)
  }
}*/

@c4("AvailabilityApp") class Monitoring(publisher: Publisher) {
  def publish(
    time: Long, updatePeriod: Long, timeout: Long,
    path: String, headers: List[N_Header], body: okio.ByteString
  ): Context => Context = {
    val nextTime = time + updatePeriod
    val pubEvents = publisher.publish(ByPathHttpPublication(path, headers, body), _+updatePeriod+timeout)
    TxAdd(pubEvents).andThen(SleepUntilKey.set(Instant.ofEpochMilli(nextTime)))
  }
}

@c4assemble("AvailabilityApp") class AvailabilityAssembleBase(updateDef: Long = 3000, timeoutDef: Long = 3000)(
  monitoring: Monitoring
) {
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    settings: Values[C_AvailabilitySetting]
  ): Values[(SrcId, TxTransform)] = {
    val (updatePeriod, timeout) = Single.option(settings.map(s => s.updatePeriod -> s.timeout)).getOrElse((updateDef, timeoutDef))
    List(WithPK(AvailabilityTx(s"AvailabilityTx-${first.srcId}", updatePeriod, timeout)(monitoring)))
  }
}

@protocol("AvailabilityApp") object AvailabilitySettingProtocol {

  @Id(0x00f0) case class C_AvailabilitySetting(
    @Id(0x0001) srcId: String,
    @Id(0x0002) updatePeriod: Long,
    @Id(0x0003) timeout: Long
  )

}

case class AvailabilityTx(srcId: SrcId, updatePeriod: Long, timeout: Long)(
  monitoring: Monitoring
) extends TxTransform {
  def transform(local: Context): Context =
    monitoring.publish(
      System.currentTimeMillis, updatePeriod, timeout,
      "/availability", Nil, ByteString.EMPTY
    )(local)
}