
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4proto.{HasId, Protocol}

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val key: Array[Byte], val value: Array[Byte]) extends QRecord

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry, getRawQSender: ()⇒RawQSender) extends QMessages {
  import qAdapterRegistry._
  def send[M<:Product](message: LEvent[M]): Unit =
    getRawQSender().send(toRecord(message))
  def toRecord[M<:Product](message: LEvent[M]): QRecord = {
    val valueAdapter =
      byName(message.className).asInstanceOf[ProtoAdapter[Product] with HasId]
    val rawKey =
      keyAdapter.encode(QProtocol.TopicKey(message.srcId, valueAdapter.id))
    val rawValue = message.value.map(valueAdapter.encode).getOrElse(Array.empty)
    new QRecordImpl(message.to, rawKey, rawValue)
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
          (if(rawValue.length > 0) valueAdapter.decode(rawValue) ::
            Nil else Nil)
      }
  }
}


class QMessageMapperFactoryImpl(qAdapterRegistry: QAdapterRegistry) extends QMessageMapperFactory {
  def create(messageMappers: List[MessageMapper[_]]): QMessageMapper = {
    val receiveById =
      messageMappers.groupBy(cl ⇒ qAdapterRegistry.byName(cl.mClass.getName).id)
        .asInstanceOf[Map[Long, List[MessageMapper[Product]]]]
    new QMessageMapperImpl(qAdapterRegistry,receiveById)
  }
}

class QMessageMapperImpl(
    qAdapterRegistry: QAdapterRegistry,
    receiveById: Map[Long, List[MessageMapper[Product]]]
) extends QMessageMapper {
  def mapMessage(mapping: MessageMapping, rec: QRecord): MessageMapping = try {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = if(rec.value.length > 0) Some(valueAdapter.decode(rec.value).asInstanceOf[Product]) else None
    val mappers = receiveById.getOrElse(key.valueTypeId,Nil)
    val className = qAdapterRegistry.nameById(key.valueTypeId)
    val message = LEvent(StateTopicName(mapping.actorName), key.srcId, className, value)
    val res = (mapping /: mappers)((res,mapper)⇒mapper.mapMessage(res,message))
    val errors = ErrorsKey.of(res.world)
    if(errors.nonEmpty) throw new Exception(errors.toString)
    res
  } catch {
    case e: Exception ⇒ mapping.add() // ??? exception to record
  }
}

class QAdapterRegistry(
    val adapters: List[ProtoAdapter[_] with HasId],
    val byName: Map[String,ProtoAdapter[_] with HasId],
    val byId: Map[Long,ProtoAdapter[Object]],
    val keyAdapter: ProtoAdapter[QProtocol.TopicKey],
    val nameById: Map[Long,String]
)

object QAdapterRegistry {
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters)
    val byName: Map[String,ProtoAdapter[_] with HasId] =
      adapters.map(a ⇒ a.className → a).toMap
    val keyAdapter = byName(classOf[QProtocol.TopicKey].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.TopicKey]]
    val byId: Map[Long,ProtoAdapter[Object]] =
      adapters.map(a ⇒ a.id → a.asInstanceOf[ProtoAdapter[Object]]).toMap
    val nameById = adapters.map(a ⇒ a.id → a.className).toMap
    new QAdapterRegistry(adapters, byName, byId, keyAdapter, nameById)
  }
}



