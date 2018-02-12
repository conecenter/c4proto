package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.ActorAccessProtocol.ActorAccessKey
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import ee.cone.c4proto._

@protocol object ActorAccessProtocol {
  @Id(0x006A) case class ActorAccessKey(
    @Id(0x006B) srcId: String,
    @Id(0x006C) value: String
  )
}

@assemble class ActorAccessAssemble {
  def join(
    key: SrcId,
    firsts: Values[Firstborn],
    accessKeys: Values[ActorAccessKey]
  ): Values[(SrcId,TxTransform)] = for {
    first ← firsts if accessKeys.isEmpty
  } yield WithPK(ActorAccessCreateTx(s"ActorAccessCreateTx-${first.srcId}",first))
}

case class ActorAccessCreateTx(srcId: SrcId, first: Firstborn) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(LEvent.update(ActorAccessKey(first.srcId,s"${UUID.randomUUID}")))(local)
}


@assemble class PrometheusAssemble(compressor: Compressor) {
  def join(
    key: SrcId,
    firsts: Values[Firstborn],
    accessKeys: Values[ActorAccessKey]
  ): Values[(SrcId,TxTransform)] = for {
    first ← firsts
    accessKey ← accessKeys
  } yield {
    val path = s"/${accessKey.value}-metrics"
    println(s"Prometheus metrics at $path")
    WithPK(PrometheusTx(path, compressor))
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
      case (worldKey:JoinKey[_,_], index: Map[_, _])
        if !worldKey.was && worldKey.keyAlias == "SrcId" ⇒
        s"""c4index_key_count{valClass="${worldKey.valueClassName}"}""" → index.size.toLong
    }.toList
    val metrics = memStats ::: keyCounts
    val bodyStr = metrics.sorted.map{ case (k,v) ⇒ s"$k $v $time\n" }.mkString
    val body = compressor.compress(okio.ByteString.encodeUtf8(bodyStr))
    val headers = List(Header("Content-Encoding", compressor.name))
    val nextTime = time + 15000
    val invalidateTime = nextTime + 5000
    val publication = HttpPublication(path, headers, body, Option(invalidateTime))
    TxAdd(LEvent.update(publication)).andThen(SleepUntilKey.set(Instant.ofEpochMilli(nextTime)))(local)
  }
}

