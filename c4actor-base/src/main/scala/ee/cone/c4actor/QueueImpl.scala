
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{Update, Updates}
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{HasId, PBAdapters, Protocol, ToByteString}

import scala.collection.immutable.{Map, Queue, Seq}
import java.nio.charset.StandardCharsets.UTF_8

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val value: Array[Byte]) extends QRecord

@c4component case class QMessagesImpl(qAdapterRegistry: QAdapterRegistry)(getRawQSender: ()⇒RawQSender) extends QMessages {
  //import qAdapterRegistry._
  // .map(o⇒ nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: Context): Context = {
    val updates: List[Update] = WriteModelKey.of(local).toList
    if(updates.isEmpty) return local
    //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
    val rawValue = qAdapterRegistry.updatesAdapter.encode(Updates("",updates))
    val rec = new QRecordImpl(InboxTopicName(),rawValue)
    val debugStr = WriteModelDebugKey.of(local).map(_.toString).mkString("\n---\n")
    val debugRec = new QRecordImpl(LogTopicName(),debugStr.getBytes(UTF_8))
    val List(offset,_)= getRawQSender().send(List(rec,debugRec))
    Function.chain(Seq(
      WriteModelKey.set(Queue.empty),
      WriteModelDebugKey.set(Queue.empty),
      OffsetWorldKey.set(offset)
    ))(local)
  }
}

@c4component case class ToUpdateImpl(qAdapterRegistry: QAdapterRegistry) extends ToUpdate {
  def toUpdate[M <: Product](message: LEvent[M]): Update = {
    val valueAdapter = qAdapterRegistry.byName(message.className)
    val byteString = ToByteString(message.value.map(valueAdapter.encode).getOrElse(Array.empty))
    Update(message.srcId, valueAdapter.id, byteString)
  }
}

object QAdapterRegistryFactory {
  private def checkToMap[K,V](pairs: Seq[(K,V)]): Map[K,V] =
    pairs.groupBy(_._1).transform((k,l)⇒Single(l.toList)._2)
  def apply(protocols: List[PBAdapters]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters).asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = checkToMap(adapters.map(a ⇒ a.className → a))
    val updatesAdapter = byName(classOf[QProtocol.Updates].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.Updates]]
    val byId = checkToMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new InnerQAdapterRegistry(byName, byId, updatesAdapter)
  }
}

class InnerQAdapterRegistry(
  val byName: Map[String,ProtoAdapter[Product] with HasId],
  val byId: Map[Long,ProtoAdapter[Product] with HasId],
  val updatesAdapter: ProtoAdapter[QProtocol.Updates]
) extends QAdapterRegistry

@c4component case class QAdapterRegistryImpl(protocols: List[PBAdapters])(
  inner: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
) extends QAdapterRegistry {
  def byName: Map[String, ProtoAdapter[Product] with HasId] = inner.byName
  def byId: Map[Long, ProtoAdapter[Product] with HasId] = inner.byId
  def updatesAdapter: ProtoAdapter[Updates] = inner.updatesAdapter
}


@c4component @listed case class LocalQAdapterRegistryInit(qAdapterRegistry: QAdapterRegistry) extends ToInject {
  def toInject: List[Injectable] = QAdapterRegistryKey.set(qAdapterRegistry)
}

object NoRawQSender extends RawQSender {
  def send(recs: List[QRecord]): List[Long] = Nil
}