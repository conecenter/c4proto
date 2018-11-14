package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, JoinKey, assemble}
import ee.cone.c4gate.ActorAccessProtocol.ActorAccessKey
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import ee.cone.c4proto.{Id, Protocol, protocol}
import okio.ByteString

@protocol object ActorAccessProtocol extends Protocol {
  @Id(0x006A) case class ActorAccessKey(
    @Id(0x006B) srcId: String,
    @Id(0x006C) value: String
  )
}

@assemble class ActorAccessAssemble extends Assemble {
  def join(
    key: SrcId,
    first: Each[Firstborn],
    accessKeys: Values[ActorAccessKey]
  ): Values[(SrcId,TxTransform)] =
    if(accessKeys.nonEmpty) Nil
    else List(WithPK(ActorAccessCreateTx(s"ActorAccessCreateTx-${first.srcId}",first)))
}

case class ActorAccessCreateTx(srcId: SrcId, first: Firstborn) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(LEvent.update(ActorAccessKey(first.srcId,s"${UUID.randomUUID}")))(local)
}

@assemble class PrometheusAssemble(compressor: Compressor) extends Assemble {
  def join(
    key: SrcId,
    first: Each[Firstborn],
    accessKey: Each[ActorAccessKey]
  ): Values[(SrcId,TxTransform)] = {
    val path = s"/${accessKey.value}-metrics"
    println(s"Prometheus metrics at $path")
    List(WithPK(PrometheusTx(path, compressor)))
  }
}

case class PrometheusTx(path: String, compressor: Compressor) extends TxTransform {
  def transform(local: Context): Context = {
    val time = System.currentTimeMillis
    val runtime = Runtime.getRuntime
    val memStats: List[(String, Long)] = List( //seems to be: max > total > free
      "runtime_mem_max" → runtime.maxMemory,
      "runtime_mem_total" → runtime.totalMemory,
      "runtime_mem_free" → runtime.freeMemory
    )
    val keyCounts: List[(String, Long)] = local.assembled.collect {
      case (worldKey:JoinKey, index: Map[_, _])
        if !worldKey.was && worldKey.keyAlias == "SrcId" ⇒
        s"""c4index_key_count{valClass="${worldKey.valueClassName}"}""" → index.size.toLong
    }.toList
    val metrics = memStats ::: keyCounts
    val bodyStr = metrics.sorted.map{ case (k,v) ⇒ s"$k $v $time\n" }.mkString
    val body = compressor.compress(okio.ByteString.encodeUtf8(bodyStr))
    val headers = List(Header("Content-Encoding", compressor.name))
    Monitoring.publish(time, 15000, 5000, path, headers, body)(local)
  }
}

object Monitoring {
  def publish(
    time: Long, updatePeriod: Long, timeout: Long,
    path: String, headers: List[Header], body: okio.ByteString
  ): Context⇒Context = {
    val nextTime = time + updatePeriod
    val invalidateTime = nextTime + timeout
    val publication = HttpPublication(path, headers, body, Option(invalidateTime))
    TxAdd(LEvent.update(publication)).andThen(SleepUntilKey.set(Instant.ofEpochMilli(nextTime)))
  }
}

@assemble class AvailabilityAssemble extends Assemble {
  def join(
    key: SrcId,
    first: Each[Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(AvailabilityTx(s"AvailabilityTx-${first.srcId}")))
}

case class AvailabilityTx(srcId: SrcId) extends TxTransform {
  def transform(local: Context): Context =
    Monitoring.publish(
      System.currentTimeMillis, 3000, 3000,
      "/availability", Nil, ByteString.EMPTY
    )(local)
}