package ee.cone.c4proto

import ee.cone.c4proto.Types.{SrcId, Values, World}
import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.QProtocol.TopicKey


////////////////////
/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QReceiverJoin extends Join3(
  By.unit(classOf[QAdapterRegistry]),
  By.unit(classOf[Reducer]),
  By.unit(classOf[Receiver[_]]),
  By.unit(classOf[QReceiver])
) {
  def join(
    qAdapterRegistries: Values[QAdapterRegistry],
    reducers: Values[Reducer],
    receivers: Values[Receiver[_]]
  ): Values[(Unit, QReceiver)] = {
    val qAdapterRegistry = Single(qAdapterRegistries)
    val byId: Map[Long,ProtoAdapter[Object]] =
      qAdapterRegistry.adapters.map(a ⇒ a.id → a.asInstanceOf[ProtoAdapter[Object]]).toMap
    val nameById = qAdapterRegistry.adapters.map(a ⇒ a.id → a.className).toMap
    val receiveById = receivers
      .map{ receiver ⇒ qAdapterRegistry.byName(receiver.cl.getName).id → receiver.handler }
      .asInstanceOf[List[(Long,(World,Object)⇒World)]].toMap
    val reducer = Single(reducers)
    val receiver = new QReceiverImpl(byId,nameById,qAdapterRegistry.keyAdapter,receiveById,reducer)
    List(()→receiver)
  }
  def sort(values: Iterable[QReceiver]): List[QReceiver] = List(Single(values.toList))
}

class QReceiverImpl(
    byId: Map[Long,ProtoAdapter[Object]],
    nameById: Map[Long,String],
    keyAdapter: ProtoAdapter[TopicKey],
    receiveById: Map[Long,(World,Object)⇒World],
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
    receiveById(key.valueTypeId)(world, value)
  }
}

class QAdapterRegistry(
    val adapters: List[ProtoAdapter[_] with HasId],
    val byName: Map[String,ProtoAdapter[_] with HasId],
    val keyAdapter: ProtoAdapter[QProtocol.TopicKey]
)

class QAdapterRegistryJoin extends Join1(
  By.unit(classOf[Protocol]),
  By.unit(classOf[QAdapterRegistry])
) {
  def join(protocols: Values[Protocol]): Values[(Unit, QAdapterRegistry)] = {
    val adapters = protocols.flatMap(_.adapters)
    val byName: Map[String,ProtoAdapter[_] with HasId] =
      adapters.map(a ⇒ a.className → a).toMap
    val keyAdapter = byName(classOf[QProtocol.TopicKey].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.TopicKey]]
    val qAdapterRegistry = new QAdapterRegistry(adapters, byName, keyAdapter)
    List(()→qAdapterRegistry)
  }
  def sort(values: Iterable[QAdapterRegistry]): List[QAdapterRegistry] = List(Single(values.toList))
}

class QSenderJoin extends Join2(
  By.unit(classOf[QAdapterRegistry]),
  By.unit(classOf[RawQSender]),
  By.unit(classOf[QSender])
) {
  def join(
    qAdapterRegistries: Values[QAdapterRegistry],
    rawQSenders: Values[RawQSender]
  ): Values[(Unit, QSender)] = {
    List(()→new QSenderImpl(Single(qAdapterRegistries),Single(rawQSenders)))
  }
  def sort(values: Iterable[QSender]): List[QSender] = List(Single(values.toList))
}

class QSenderImpl(qAdapterRegistry: QAdapterRegistry, forward: RawQSender) extends QSender {
  import qAdapterRegistry._
  def sendUpdate[M](srcId: SrcId, value: M): Unit =
    send(srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def sendDelete[M](srcId: SrcId, cl: Class[M]): Unit = send(srcId, cl, None)
  private def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]
  private def send[M](srcId: SrcId, cl: Class[M], value: Option[M]): Unit = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    forward.send(rawKey, rawValue)
  }
}
