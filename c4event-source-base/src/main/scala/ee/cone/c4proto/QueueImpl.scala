package ee.cone.c4proto

import ee.cone.c4proto.Types.{Index, SrcId, World}
import com.squareup.wire.ProtoAdapter

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val key: Array[Byte], val value: Array[Byte]) extends QRecord

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry, getRawQSender: ()⇒RawQSender) extends QMessages {
  import qAdapterRegistry._
  def send[M<:Product](message: Send[M]): Unit =
    getRawQSender().send(toRecord(None, message))
  def toRecord(currentActorName: Option[ActorName], message: MessageMapResult): QRecord = {
    val(topic, selectedSrcId, selectedClass, selectedValue) = message match {
      case Update(srcId, value) ⇒
        (StateTopicName(currentActorName.get), srcId, value.getClass, Option(value))
      case Delete(srcId, cl) ⇒
        (StateTopicName(currentActorName.get), srcId, cl, None:Option[Product])
      case Send(actorName, value) ⇒
        (InboxTopicName(actorName), "", value.getClass, Option(value))
    }
    val valueAdapter =
      byName(selectedClass.getName).asInstanceOf[ProtoAdapter[Product] with HasId]
    val rawKey =
      keyAdapter.encode(QProtocol.TopicKey(selectedSrcId, valueAdapter.id))
    val rawValue = selectedValue.map(valueAdapter.encode).getOrElse(Array.empty)
    new QRecordImpl(topic, rawKey, rawValue)
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

class QMessageMapperFactory(qAdapterRegistry: QAdapterRegistry, qMessages: QMessages) {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): QMessageMapper = {
    val receiveById =
      messageMappers.groupBy(cl ⇒ qAdapterRegistry.byName(cl.mClass.getName).id)
        .asInstanceOf[Map[Long, List[MessageMapper[Object]]]]
    new QMessageMapperImpl(actorName)(qAdapterRegistry,qMessages,receiveById)
  }
}

class QMessageMapperImpl(actorName: ActorName)(
    qAdapterRegistry: QAdapterRegistry,
    qMessages: QMessages,
    receiveById: Map[Long, List[MessageMapper[Object]]]
) extends QMessageMapper {
  def mapMessage(world: World, rec: QRecord): Seq[QRecord] = {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById.getOrElse(key.valueTypeId,Nil).flatMap(mapper ⇒
      mapper.mapMessage(world, value).map(qMessages.toRecord(Option(actorName),_))
    )
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



