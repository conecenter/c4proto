package ee.cone.c4proto

import ee.cone.c4proto.Types.{SrcId, World}
import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.QProtocol.TopicKey


////////////////////
/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

trait QReceiverApp {
  def qAdapterRegistry: QAdapterRegistry
  def reducer: Reducer
  def commandReceivers: List[CommandReceiver[_]]
  lazy val qReceiver: QReceiver = {
    val byId: Map[Long,ProtoAdapter[Object]] =
      qAdapterRegistry.adapters.map(a ⇒ a.id → a.asInstanceOf[ProtoAdapter[Object]]).toMap
    val nameById = qAdapterRegistry.adapters.map(a ⇒ a.id → a.className).toMap
    val receiveById = commandReceivers.map{ receiver ⇒ qAdapterRegistry.byName(receiver.className).id → receiver }
      .asInstanceOf[List[(Long,CommandReceiver[Object])]].toMap
    new QReceiverImpl(byId,nameById,qAdapterRegistry.keyAdapter,receiveById,reducer)
  }
}

class QReceiverImpl(
    byId: Map[Long,ProtoAdapter[Object]],
    nameById: Map[Long,String],
    keyAdapter: ProtoAdapter[TopicKey],
    receiveById: Map[Long,CommandReceiver[Object]],
    reducer: Reducer
) extends QReceiver {
  private def toTree(records: Iterable[QRecord]) = records.map {
    rec ⇒ (keyAdapter.decode(rec.key), rec)
  }.groupBy {
    case (topicKey, _) ⇒ topicKey.valueTypeId
  }.map {
    case (valueTypeId, keysEvents) ⇒
      val worldKey = By.It[SrcId,Object]('S',nameById(valueTypeId))
      val valueAdapter = byId(valueTypeId)
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
  def receiveEvents(world: World, records: Iterable[QRecord]): World = {
    val diff = toTree(records)
    //debug here
    reducer.reduce(world, diff)
  }

  def receiveCommand(world: World, rec: QRecord): World = {
    val key = keyAdapter.decode(rec.key)
    val valueAdapter = byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById(key.valueTypeId).handle(world, value)
  }
}

class QAdapterRegistry(
    val adapters: List[ProtoAdapter[_] with HasId],
    val byName: Map[String,ProtoAdapter[_] with HasId],
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
    new QAdapterRegistry(adapters, byName, keyAdapter)
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
    send(srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def sendDelete[M](topic: TopicName, srcId: SrcId, cl: Class[M]): Unit = send(srcId, cl, None)
  private def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]
  private def send[M](topic: TopicName, srcId: SrcId, cl: Class[M], value: Option[M]): Unit = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    forward.send(topic, rawKey, rawValue)
  }
}
