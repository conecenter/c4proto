package ee.cone.c4proto

import ee.cone.c4proto.Types.{SrcId, World}
import com.squareup.wire.ProtoAdapter


////////////////////
/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

trait QStatePartReceiverApp {
  def qAdapterRegistry: QAdapterRegistry
  def reducer: Reducer
  lazy val qStatePartReceiver: QStatePartReceiver = {
    val nameById = qAdapterRegistry.adapters.map(a ⇒ a.id → a.className).toMap
    new QStatePartReceiverImpl(qAdapterRegistry,nameById,reducer)
  }
}

trait QMessageReceiverApp {
  def qAdapterRegistry: QAdapterRegistry
  def commandReceivers: List[MessageReceiver[_]]
  lazy val qMessageReceiver: QMessageReceiver = {
    val receiveById = commandReceivers.map{ receiver ⇒ qAdapterRegistry.byName(receiver.className).id → receiver }
      .asInstanceOf[List[(Long,MessageReceiver[Object])]].toMap
    new QMessageReceiverImpl(qAdapterRegistry,receiveById)
  }
}

class QStatePartReceiverImpl(
    qAdapterRegistry: QAdapterRegistry,
    nameById: Map[Long,String],
    reducer: Reducer
) extends QStatePartReceiver {
  private def toTree(records: Iterable[QRecord]) = records.map {
    rec ⇒ (qAdapterRegistry.keyAdapter.decode(rec.key), rec)
  }.groupBy {
    case (topicKey, _) ⇒ topicKey.valueTypeId
  }.map {
    case (valueTypeId, keysEvents) ⇒
      val worldKey = By.It[SrcId,Object]('S',nameById(valueTypeId))
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
  var world: World = Map()
  def receiveStateParts(records: Iterable[QRecord]): Unit = {
    val diff = toTree(records)
    //debug here
    val nextWorld = reducer.reduce(world, diff)
    synchronized{ world = nextWorld }
  }
}

class QMessageReceiverImpl(
    qAdapterRegistry: QAdapterRegistry,
    receiveById: Map[Long,MessageReceiver[Object]]
) extends QMessageReceiver {
  def receiveMessage(rec: QRecord): Unit = {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById(key.valueTypeId).receiveMessage(value)
  }
}


class QAdapterRegistry(
    val adapters: List[ProtoAdapter[_] with HasId],
    val byName: Map[String,ProtoAdapter[_] with HasId],
    val byId: Map[Long,ProtoAdapter[Object]],
    val keyAdapter: ProtoAdapter[QProtocol.TopicKey]
)

trait QAdapterRegistryApp {
  def protocols: List[Protocol]
  lazy val qAdapterRegistry: QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters)
    val byName: Map[String,ProtoAdapter[_] with HasId] =
      adapters.map(a ⇒ a.className → a).toMap
    val keyAdapter = byName(classOf[QProtocol.TopicKey].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.TopicKey]]
    val byId: Map[Long,ProtoAdapter[Object]] =
      qAdapterRegistry.adapters.map(a ⇒ a.id → a.asInstanceOf[ProtoAdapter[Object]]).toMap
    new QAdapterRegistry(adapters, byName, byId, keyAdapter)
  }
}

trait QSenderApp {
  def qAdapterRegistry: QAdapterRegistry
  def rawQSender: RawQSender
  lazy val qSender: QSender = new QSenderImpl(qAdapterRegistry, rawQSender)
}

class QSenderImpl(qAdapterRegistry: QAdapterRegistry, forward: RawQSender) extends QSender {
  import qAdapterRegistry._
  def sendUpdate[M](topic: TopicName, srcId: SrcId, value: M): Unit =
    send(topic, srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def sendDelete[M](topic: TopicName, srcId: SrcId, cl: Class[M]): Unit =
    send(topic, srcId, cl, None)
  private def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]
  private def send[M](topic: TopicName, srcId: SrcId, cl: Class[M], value: Option[M]): Unit = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    forward.send(topic, rawKey, rawValue)
  }
}
