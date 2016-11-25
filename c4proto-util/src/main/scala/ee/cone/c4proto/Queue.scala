package ee.cone.c4proto

import ee.cone.c4proto.Types.{SrcId, World}
import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.QProtocol.TopicKey

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

trait QRecord {
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Long
}

////////////////////
/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

case object ReceiverKey extends EventKey[Receiver[_]]
class Receiver[M](val cl: Class[M], val handler: M⇒Unit)

object QRecords {
  //CoHandler(ReceiverKey)(new Receiver(classOf[String])((s:String)⇒()))
  def apply(handlerLists: CoHandlerLists)(forward: (Array[Byte], Array[Byte]) ⇒ Unit): QRecords = {
    val adapters = handlerLists.list(ProtocolKey).flatMap(_.adapters)
    val byName: Map[String,ProtoAdapter[_] with HasId] =
      adapters.map(a ⇒ a.className → a).toMap
    val byId: Map[Long,ProtoAdapter[Object]] =
      adapters.map(a ⇒ a.id → a.asInstanceOf[ProtoAdapter[Object]]).toMap
    val nameById = adapters.map(a ⇒ a.id → a.className).toMap
    val keyAdapter = byName(classOf[QProtocol.TopicKey].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.TopicKey]]
    val receiveById = handlerLists.list(ReceiverKey)
      .map{ receiver ⇒ byName(receiver.cl.getName).id → receiver.handler }
      .asInstanceOf[List[(Long,Object⇒Unit)]].toMap
    new QRecords(byName,byId,nameById,keyAdapter,receiveById,forward)
  }
}

class QRecords(
    byName: Map[String,ProtoAdapter[_]],
    byId: Map[Long,ProtoAdapter[Object]],
    nameById: Map[Long,String],
    keyAdapter: ProtoAdapter[TopicKey],
    receiveById: Map[Long,Object⇒Unit],
    forward: (Array[Byte], Array[Byte]) ⇒ Unit
) {
  def byInstance[M](model: M): ProtoAdapter[M] =
    byClass(model.getClass.asInstanceOf[Class[M]])
  def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]

  def toTree(records: Iterable[QRecord]): World = records.map {
    rec ⇒ (keyAdapter.decode(rec.key), rec)
  }.groupBy {
    case (topicKey, _) ⇒ topicKey.valueTypeId
  }.map {
    case (valueTypeId, keysEvents) ⇒
      val worldKey = BySrcId.It[Object](nameById(valueTypeId))
      val valueAdapter = byId(valueTypeId)
      worldKey → keysEvents.groupBy {
        case (topicKey, _) ⇒ topicKey.srcId
      }.map { case(srcId,keysEventsI) ⇒
        val (topicKey, rec) = keysEventsI.last
        val rawValue = rec.value
        (srcId:Object) → (if (rawValue.length > 0) valueAdapter.decode(rawValue) :: Nil else Nil)
      }
  }
  def receive(rec: QRecord): Unit = {
    val key = keyAdapter.decode(rec.key)
    val valueAdapter = byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById(key.valueTypeId)(value)
  }
  def sendUpdate[M](srcId: SrcId, value: M): Unit =
    send(srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def sendDelete[M](srcId: SrcId, cl: Class[M]): Unit = send(srcId, cl, None)
  private def send[M](srcId: SrcId, cl: Class[M], value: Option[M]): Unit = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    forward(rawKey, rawValue)
  }
}
