package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, JoinKey, assemble}
import ee.cone.c4gate.ActorAccessProtocol.ActorAccessKey
import ee.cone.c4gate.HttpProtocol.HttpPublication
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object ActorAccessProtocol extends Protocol {
  @Id(0x006A) case class ActorAccessKey(
    @Id(0x006B) srcId: String,
    @Id(0x006C) value: String
  )
}

@assemble class ActorAccessAssemble extends Assemble {
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

@assemble class PrometheusAssemble extends Assemble {
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
    WithPK(PrometheusTx(path))
  }
}

case class PrometheusTx(path: String) extends TxTransform {
  def transform(local: Context): Context = {
    val time = System.currentTimeMillis
    val bodyStr = local.assembled.collect {
      case (worldKey:JoinKey[_,_], index: Map[_, _])
        if !worldKey.was && worldKey.keyAlias == "SrcId" ⇒
        worldKey.valueClassName → index.size
    }.toList.sorted.map{
      case (k,v) ⇒ s"""c4index_key_count{valClass="$k"} $v $time"""
    }.mkString("","\n","\n")
    val body = okio.ByteString.encodeUtf8(bodyStr)
    //todo move gzipper to base and use it here
    val nextTime = time + 15000
    val invalidateTime = nextTime + 5000
    val publication = HttpPublication(path, Nil, body, Option(invalidateTime))
    TxAdd(LEvent.update(publication)).andThen(SleepUntilKey.set(Instant.ofEpochMilli(nextTime)))(local)
  }
}

