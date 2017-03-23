
package ee.cone.c4actor

import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{Offset, TopicKey, Update, Updates}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble.{Single, WorldKey}
import ee.cone.c4proto.{HasId, Protocol}

import scala.collection.immutable.Seq

import java.nio.charset.StandardCharsets.UTF_8

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val key: Array[Byte], val value: Array[Byte]) extends QRecord {
  def offset: Option[Long] = None
}

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry, getRawQSender: ()⇒RawQSender) extends QMessages {
  import qAdapterRegistry._
  // .map(o⇒ nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: World): World = {
    val tx = TxKey.of(local)
    val updates: List[Update] = tx.toSend.toList
    if(updates.isEmpty) return local
    //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
    val rawValue = qAdapterRegistry.updatesAdapter.encode(Updates("",updates))
    val rec = new QRecordImpl(InboxTopicName(),Array.empty,rawValue)
    val debugStr = tx.toDebug.map(_.toString).mkString("\n---\n")
    val debugRec = new QRecordImpl(LogTopicName(),Array.empty,debugStr.getBytes(UTF_8))
    val List(offset,_)= getRawQSender().send(List(rec,debugRec))
    OffsetWorldKey.set(offset+1)(local)
  }
  def toUpdate[M<:Product](message: LEvent[M]): Update = {
    val valueAdapter = byName(message.className)
    val bytes = message.value.map(valueAdapter.encode).getOrElse(Array.empty)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    Update(message.srcId, valueAdapter.id, byteString)
  }
  def toRecord(topicName: TopicName, update: Update): QRecord = {
    val rawKey = keyAdapter.encode(TopicKey(update.srcId, update.valueTypeId))
    val rawValue = update.value.toByteArray
    new QRecordImpl(topicName, rawKey, rawValue)
  }
  def offsetUpdate(value: Long): List[Update] =
    LEvent.update(Offset("", value)).toList.map(toUpdate)
  def toRecords(actorName: ActorName, rec: QRecord): List[QRecord] = {
    //if(rec.key.length > 0) throw new Exception
    val updates = qAdapterRegistry.updatesAdapter.decode(rec.value).updates
    val relevantUpdates =
      updates.filter(u ⇒ qAdapterRegistry.byId.contains(u.valueTypeId)) :::
        offsetUpdate(rec.offset.get + 1)
    relevantUpdates.map(toRecord(StateTopicName(actorName),_))
  }
  def worldOffset: World ⇒ Long = world ⇒
    Single(By.srcId(classOf[Offset]).of(world).getOrElse("",List(Offset("",0L)))).value
  def toTree(records: Iterable[QRecord]): Map[WorldKey[Index[SrcId,Product]], Index[SrcId,Product]] = records.map {
    rec ⇒ (qAdapterRegistry.keyAdapter.decode(rec.key), rec)
  }.groupBy {
    case (topicKey, rec) ⇒ topicKey.valueTypeId
  }.flatMap { case (valueTypeId, keysEvents) ⇒
    qAdapterRegistry.byId.get(valueTypeId).map(valueAdapter ⇒
      By.srcId[Product](valueAdapter.className) → keysEvents.groupBy {
        case (topicKey, _) ⇒ topicKey.srcId
      }.map { case (srcId, keysEventsI) ⇒
        val (topicKey, rec) = keysEventsI.last
        val rawValue = rec.value
        val values =
          if(rawValue.length > 0) valueAdapter.decode(rawValue) :: Nil else Nil
        srcId → values
      }
    )
  }
}

class QAdapterRegistry(
    val adapters: List[ProtoAdapter[Product] with HasId],
    val byName: Map[String,ProtoAdapter[Product] with HasId],
    val byId: Map[Long,ProtoAdapter[Product] with HasId],
    val keyAdapter: ProtoAdapter[QProtocol.TopicKey],
    val updatesAdapter: ProtoAdapter[QProtocol.Updates],
    val nameById: Map[Long,String]
)

object QAdapterRegistry {
  private def checkToMap[K,V](pairs: Seq[(K,V)]): Map[K,V] =
    pairs.groupBy(_._1).transform((k,l)⇒Single(l.toList)._2)
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters).asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = checkToMap(adapters.map(a ⇒ a.className → a))
    val keyAdapter = byName(classOf[QProtocol.TopicKey].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.TopicKey]]
    val updatesAdapter = byName(classOf[QProtocol.Updates].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.Updates]]
    val byId = checkToMap(adapters.map(a ⇒ a.id → a))
    val nameById = checkToMap(adapters.map(a ⇒ a.id → a.className))
    new QAdapterRegistry(adapters, byName, byId, keyAdapter, updatesAdapter, nameById)
  }
}

