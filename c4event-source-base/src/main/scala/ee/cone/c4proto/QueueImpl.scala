package ee.cone.c4proto

import ee.cone.c4proto.Types.{Index, SrcId}
import com.squareup.wire.ProtoAdapter

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val streamKey: StreamKey, val key: Array[Byte], val value: Array[Byte]) extends QRecord

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry) extends QMessages {
  import qAdapterRegistry._
  def toRecord(streamKey: StreamKey, message: Product): QRecord = {
    val(srcId,cl,value) = message match {
      case (srcId: SrcId, cl: Class[_]) ⇒ (srcId, cl, None:Option[Product])
      case (srcId: SrcId, value: Product) ⇒ (srcId, value.getClass, Option(value))
      case value: Product ⇒ ("", value.getClass, Option(value))
    }
    val valueAdapter = byName(cl.getName).asInstanceOf[ProtoAdapter[Product] with HasId]
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    new QRecordImpl(streamKey, rawKey, rawValue)
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

object QMessageMapperImpl {
  def apply(qAdapterRegistry: QAdapterRegistry, qMessages: QMessages, messageMappers: List[MessageMapper[_]]): QMessageMapper = {
    val receiveById =
      messageMappers.groupBy(_.streamKey).mapValues(_.groupBy(cl⇒qAdapterRegistry.byName(cl.mClass.getName).id))
        .asInstanceOf[Map[StreamKey, Map[Long, List[MessageMapper[Object]]]]]
    new QMessageMapperImpl(qAdapterRegistry,qMessages,receiveById)
  }
}

class QMessageMapperImpl(
    qAdapterRegistry: QAdapterRegistry,
    qMessages: QMessages,
    receiveById: Map[StreamKey, Map[Long, List[MessageMapper[Object]]]]
) extends QMessageMapper {
  def streamKeys: List[StreamKey] =
    receiveById.keys.toList.filter(_.from.nonEmpty).sortBy(s⇒s"${s.from}->${s.to}")
  def mapMessage(streamKey: StreamKey, rec: QRecord): Seq[QRecord] = {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById(rec.streamKey).getOrElse(key.valueTypeId,Nil)
      .flatMap(_.mapMessage(value)).map(qMessages.toRecord(streamKey,_))
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



