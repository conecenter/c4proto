package ee.cone.c4proto

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import ee.cone.c4proto.Types.{Index, SrcId, World}
import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.By.It


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

trait QMessageMapperApp {
  def qAdapterRegistry: QAdapterRegistry
  def commandReceivers: List[MessageMapper[_]]
  lazy val qMessageMapper: QMessageMapper = {
    val receiveById =
      commandReceivers.groupBy(_.streamKey).mapValues(_.groupBy(cl⇒qAdapterRegistry.byName(cl.mClass.getName).id))
        .asInstanceOf[Map[StreamKey, Map[Long, List[MessageMapper[Object]]]]]
      /*
      commandReceivers.map{ receiver ⇒
        qAdapterRegistry.byName(receiver.mClass.getName).id → receiver
      }.asInstanceOf[List[(Long,MessageMapper[Object])]].toMap*/
    new QMessageMapperImpl(qAdapterRegistry,receiveById)
  }
}

trait QMessagesApp {
  def qAdapterRegistry: QAdapterRegistry
  def rawQSender: RawQSender
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry)
}

class QStatePartReceiverImpl(
    qAdapterRegistry: QAdapterRegistry,
    nameById: Map[Long,String],
    reducer: Reducer
) extends QStatePartReceiver {
  private def toTree(records: Iterable[QConsumerRecord]): Map[WorldKey[_],Index[Object,Object]] = records.map {
    rec ⇒ (qAdapterRegistry.keyAdapter.decode(rec.key), rec)
  }.groupBy {
    case (topicKey, _) ⇒ topicKey.valueTypeId
  }.map {
    case (valueTypeId, keysEvents) ⇒
      val worldKey: WorldKey[Index[SrcId,Object]] = By.It[SrcId,Object]('S',nameById(valueTypeId))
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
  def reduce(world: World, records: Iterable[QConsumerRecord]): World = {
    val diff = toTree(records)
    //debug here
    reducer.reduce(world, diff)
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
    val keyAdapter: ProtoAdapter[QProtocol.TopicKey]
)

trait QAdapterRegistryApp extends ProtocolsApp {
  override def protocols: List[Protocol] = QProtocol :: super.protocols
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

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry) extends QMessages {
  import qAdapterRegistry._
  def update[M](srcId: SrcId, value: M): QProducerRecord =
    inner(srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def delete[M](srcId: SrcId, cl: Class[M]): QProducerRecord =
    inner(srcId, cl, None)
  private def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]
  private def inner[M](srcId: SrcId, cl: Class[M], value: Option[M]): QProducerRecord = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    new QProducerRecord(rawKey, rawValue)
  }
}

////

object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
}

trait PoolApp {
  lazy val pool: Pool = new PoolImpl
}

class PoolImpl extends Pool {
  def make(): ExecutorService = {
    val pool = Executors.newCachedThreadPool() //newWorkStealingPool
    OnShutdown(()⇒{
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    pool
  }
}