package ee.cone.c4proto

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import ee.cone.c4proto.Types.{Index, SrcId}
import com.squareup.wire.ProtoAdapter

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry) extends QMessages {
  import qAdapterRegistry._

  def update[M](srcId: SrcId, value: M): QProducerRecord =
    inner(srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def delete[M](srcId: SrcId, cl: Class[M]): QProducerRecord =
    inner(srcId, cl, None)

  def toProducerRecord(message: QMessage): QProducerRecord = ???
  private def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]
  private def inner[M](srcId: SrcId, cl: Class[M], value: Option[M]): QProducerRecord = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    new QProducerRecord(rawKey, rawValue)
  }
  ////
  def toTree(records: Iterable[QConsumerRecord]): Map[WorldKey[_],Index[Object,Object]] = records.map {
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
  def apply(qAdapterRegistry: QAdapterRegistry, messageMappers: List[MessageMapper[_]]): QMessageMapper = {
    val receiveById =
      messageMappers.groupBy(_.streamKey).mapValues(_.groupBy(cl⇒qAdapterRegistry.byName(cl.mClass.getName).id))
        .asInstanceOf[Map[StreamKey, Map[Long, List[MessageMapper[Object]]]]]
    /*
    commandReceivers.map{ receiver ⇒
      qAdapterRegistry.byName(receiver.mClass.getName).id → receiver
    }.asInstanceOf[List[(Long,MessageMapper[Object])]].toMap*/
    new QMessageMapperImpl(qAdapterRegistry,receiveById)
  }
}

class QMessageMapperImpl(
    qAdapterRegistry: QAdapterRegistry,
    receiveById: Map[StreamKey, Map[Long, List[MessageMapper[Object]]]]
) extends QMessageMapper {
  def streamKeys: List[StreamKey] =
    receiveById.keys.toList.filter(_.from.nonEmpty).sortBy(s⇒s"${s.from}->${s.to}")
  def mapMessage(rec: QConsumerRecord): Seq[QProducerRecord] = {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById(rec.streamKey).getOrElse(key.valueTypeId,Nil).flatMap(_.mapMessage(value))
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



