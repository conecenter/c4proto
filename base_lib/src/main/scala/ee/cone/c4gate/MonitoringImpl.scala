package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4multi}
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

@c4assemble("ActorAccessApp") class ActorAccessAssembleBase(
  actorAccessCreateTxFactory: ActorAccessCreateTxFactory,
){
  def join(
    key: SrcId,
    first: Each[S_Firstborn],
    accessKeys: Values[C_ActorAccessKey]
  ): Values[(SrcId, TxTransform)] =
    if (accessKeys.nonEmpty) Nil
    else List(WithPK(actorAccessCreateTxFactory.create(s"ActorAccessCreateTx-${first.srcId}", first)))
}

@c4multi("ActorAccessApp") final case class ActorAccessCreateTx(srcId: SrcId, first: S_Firstborn)(
  txAdd: LTxAdd,
) extends TxTransform {
  def transform(local: Context): Context =
    txAdd.add(LEvent.update(C_ActorAccessKey(first.srcId, s"${UUID.randomUUID}")))(local)
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

@c4("AvailabilityApp") final class Monitoring(
  publisher: Publisher,
  txAdd: LTxAdd,
) {
  def publish(
    time: Long, updatePeriod: Long, timeout: Long,
    path: String, headers: List[N_Header], body: okio.ByteString
  ): Context => Context = {
    val nextTime = time + updatePeriod
    val pubEvents = publisher.publish(ByPathHttpPublication(path, headers, body), _+updatePeriod+timeout)
    txAdd.add(pubEvents).andThen(SleepUntilKey.set(Instant.ofEpochMilli(nextTime)))
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

@c4("AvailabilityApp") final class EnableAvailabilityScaling extends EnableSimpleScaling(classOf[AvailabilityTx])

case class AvailabilityTx(srcId: SrcId, updatePeriod: Long, timeout: Long)(
  monitoring: Monitoring
) extends TxTransform {
  def transform(local: Context): Context =
    monitoring.publish(
      System.currentTimeMillis, updatePeriod, timeout,
      "/availability", Nil, ByteString.EMPTY
    )(local)
}

/*
* We need to ensure main observes data from put-snapshot, so it publishes /seen/response/...
* */

//@c4assemble("AvailabilityApp") class SnapshotPutAckAssembleBase(
//  responseSeenTxFactory: ResponseSeenTxFactory
//){
//  type SeenKey = SrcId
//  def toSeen(
//    key: SrcId,
//    httpPublication: Each[ByPathHttpPublication]
//  ): Values[(SeenKey, ByPathHttpPublication)] =
//    if(httpPublication.path.startsWith("/response/"))
//      List(s"/seen/${httpPublication.path}"->httpPublication) else Nil
//
//  def toTx(
//    key: SrcId,
//    @by[SeenKey] needHttpPublication: Each[ByPathHttpPublication],
//    httpPublications: Values[ByPathHttpPublication],
//  ): Values[(SrcId, TxTransform)] =
//    if(httpPublications.nonEmpty) Nil else List(WithPK(responseSeenTxFactory.create(needHttpPublication.path)))
//}
//
//@c4multi("AvailabilityApp") final case class ResponseSeenTx(path: String)(
//  publisher: Publisher,
//  txAdd: LTxAdd,
//) extends TxTransform {
//  def transform(local: Context): Context =
//    txAdd.add(publisher.publish(ByPathHttpPublication(path, Nil, ByteString.EMPTY), _ + 1000 * 60 * 15))(local)
//}
