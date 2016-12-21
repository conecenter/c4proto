
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{TopicKey, Update, Updates}
import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4proto.{HasId, Protocol}

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val key: Array[Byte], val value: Array[Byte], val offset: Option[Long]) extends QRecord

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry, getRawQSender: ()⇒RawQSender, setOffset: (Object,Long)⇒Object) extends QMessages {
  import qAdapterRegistry._
  def send[M<:Product](tx: WorldTx): Option[Long] = {
    val updates = tx.toSend.toList
    if(updates.isEmpty) return None
    val rawValue = qAdapterRegistry.updatesAdapter.encode(Updates(updates))
    val rec = new QRecordImpl(InboxTopicName(),Array.empty,rawValue,None)
    Option(getRawQSender().send(rec))
  }
  def toUpdate[M<:Product](message: LEvent[M]): Update = {
    val valueAdapter =
      byName(message.className).asInstanceOf[ProtoAdapter[Product] with HasId]
    val bytes = message.value.map(valueAdapter.encode).getOrElse(Array.empty)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    Update(message.srcId, valueAdapter.id, byteString)
  }
  def toRecord(topicName: TopicName, update: Update): QRecord = {
    val rawKey = keyAdapter.encode(TopicKey(update.srcId, update.valueTypeId))
    val rawValue = update.value.toByteArray
    new QRecordImpl(topicName, rawKey, rawValue, None)
  }
  def toRecords(actorName: ActorName, rec: QRecord): List[QRecord] = {
    if(rec.key.length > 0) throw new Exception
    val updates = qAdapterRegistry.updatesAdapter.decode(rec.value).updates
    val relevantUpdates =
      updates.filter(u⇒qAdapterRegistry.byId.contains(u.valueTypeId))
    relevantUpdates.map(toRecord(StateTopicName(actorName),_))
  }

  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]] = records.map {
    rec ⇒ (qAdapterRegistry.keyAdapter.decode(rec.key), rec)
  }.groupBy {
    case (topicKey, _) ⇒ topicKey.valueTypeId
  }.map {
    case (valueTypeId, keysEvents) ⇒
      val worldKey: WorldKey[Index[SrcId,Object]] =
        By.It[SrcId,Object]('S',qAdapterRegistry.nameById(valueTypeId))
      val valueAdapter = qAdapterRegistry.byId(valueTypeId)
      worldKey → keysEvents.groupBy {
        case (topicKey, _) ⇒ topicKey.srcId
      }.map { case (srcId, keysEventsI) ⇒
        val (topicKey, rec) = keysEventsI.last
        val rawValue = rec.value
        (srcId: Object) →
          (if(rawValue.length > 0) setOffset(valueAdapter.decode(rawValue), rec.offset.get) ::
            Nil else Nil)
      }
  }
}

/*
class QMessageMapperFactoryImpl(qAdapterRegistry: QAdapterRegistry) extends QMessageMapperFactory {
  def create(messageHandlers: List[MessageHandler[_]]): QMessageMapper = {
    val receiveById =
      messageHandlers.groupBy(cl ⇒ qAdapterRegistry.byName(cl.mClass.getName).id)
        .asInstanceOf[Map[Long, List[MessageHandler[Product]]]]
    new QMessageMapperImpl(qAdapterRegistry,receiveById)
  }
}


class QMessageMapperImpl(
    qAdapterRegistry: QAdapterRegistry,
    receiveById: Map[Long, List[MessageHandler[Product]]]
) extends QMessageMapper {
  def mapMessage(mapping: MessageMapping, rec: QRecord): MessageMapping = try {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = if(rec.value.length > 0) Some(valueAdapter.decode(rec.value).asInstanceOf[Product]) else None
    val mappers = receiveById.getOrElse(key.valueTypeId,Nil)
    mappers.foreach(_.handleMessage(value.get))
    if(mappers.nonEmpty) mapping else {
      val className = qAdapterRegistry.nameById(key.valueTypeId)
      val message = LEvent(key.srcId, className, value)
      mapping.add(message) // pass to state by default
    }
  } catch {
    case e: Exception ⇒ mapping.add() // ??? exception to record
  }
}
*/

class QAdapterRegistry(
    val adapters: List[ProtoAdapter[_] with HasId],
    val byName: Map[String,ProtoAdapter[_] with HasId],
    val byId: Map[Long,ProtoAdapter[Object]],
    val keyAdapter: ProtoAdapter[QProtocol.TopicKey],
    val updatesAdapter: ProtoAdapter[QProtocol.Updates],
    val nameById: Map[Long,String]
)

object QAdapterRegistry {
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters)
    val byName: Map[String,ProtoAdapter[_] with HasId] =
      adapters.map(a ⇒ a.className → a).toMap
    val keyAdapter = byName(classOf[QProtocol.TopicKey].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.TopicKey]]
    val updatesAdapter = byName(classOf[QProtocol.Updates].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.Updates]]
    val byId: Map[Long,ProtoAdapter[Object]] =
      adapters.map(a ⇒ a.id → a.asInstanceOf[ProtoAdapter[Object]]).toMap
    val nameById = adapters.map(a ⇒ a.id → a.className).toMap
    new QAdapterRegistry(adapters, byName, byId, keyAdapter, updatesAdapter, nameById)
  }
}



