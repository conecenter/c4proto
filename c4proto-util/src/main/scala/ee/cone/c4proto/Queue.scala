package ee.cone.c4proto

import ee.cone.c4proto.Types.World

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

trait QRecord {
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Long
}

////////////////////

class Handling[R](findAdapter: FindAdapter, val byId: Map[Long, Object ⇒ R]=Map[Long, Object ⇒ R]()) {
  def add[M](cl: Class[M])(handle: M⇒R): Handling[R] =
    new Handling[R](findAdapter, byId + (
      findAdapter.byClass(cl).id → handle.asInstanceOf[Object ⇒ R]
      ))
}

class Sender(
    //producer: KafkaProducer[Array[Byte], Array[Byte]],
    //topic: String,
    findAdapter: FindAdapter,
    toSrcId: Handling[String]
)(forward: (Array[Byte], Array[Byte]) ⇒ Unit) {
  def send(value: Object): Unit/*Future[RecordMetadata]*/ = {
    val valueAdapter = findAdapter(value)
    val srcId = toSrcId.byId(valueAdapter.id)(value)
    val key = QProtocol.TopicKey(srcId, valueAdapter.id)
    val keyAdapter = findAdapter(key)
    forward(keyAdapter.encode(key), valueAdapter.encode(value))
    //producer.send(new ProducerRecord(topic, rawKey, rawValue))
  }
}

class Receiver(findAdapter: FindAdapter, receive: Handling[Unit]){
  def receive(rec: QRecord): Unit = {
    val keyAdapter = findAdapter.byClass(classOf[QProtocol.TopicKey])
    val key = keyAdapter.decode(rec.key)
    val valueAdapter = findAdapter.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receive.byId(key.valueTypeId)(value)
    //decode(new ProtoReader(new okio.Buffer().write(bytes)))
  }
}

class QRecords(findAdapter: FindAdapter) {
  def toTree(records: Iterable[QRecord]): World = {
    val keyAdapter = findAdapter.byClass(classOf[QProtocol.TopicKey])
    records.map {
      rec ⇒ (keyAdapter.decode(rec.key), rec)
    }.groupBy {
      case (topicKey, _) ⇒ topicKey.valueTypeId
    }.map {
      case (valueTypeId, keysEvents) ⇒
        val worldKey = BySrcId.It(findAdapter.nameById(valueTypeId))
        val valueAdapter = findAdapter.byId(valueTypeId)
        worldKey → keysEvents.groupBy {
          case (topicKey, _) ⇒ topicKey.srcId
        }.map { case(srcId,keysEventsI) ⇒
          val (topicKey, rec) = keysEventsI.last
          val rawValue = rec.value
          (srcId:Object) → (if (rawValue.length > 0) valueAdapter.decode(rawValue) :: Nil else Nil)
        }
    }
  }
}

