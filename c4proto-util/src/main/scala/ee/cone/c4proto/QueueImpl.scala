package ee.cone.c4proto

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

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

trait QMessageMapperApp {
  def qAdapterRegistry: QAdapterRegistry
  def commandReceivers: List[MessageMapper[_]]
  lazy val qMessageMapper: QMessageMapper = {
    val receiveById =
      commandReceivers.groupBy(_.topic).mapValues(_.groupBy(cl⇒qAdapterRegistry.byName(cl.mClass.getName).id))
        .asInstanceOf[Map[TopicName, Map[Long, List[MessageMapper[Object]]]]]
      /*
      commandReceivers.map{ receiver ⇒
        qAdapterRegistry.byName(receiver.mClass.getName).id → receiver
      }.asInstanceOf[List[(Long,MessageMapper[Object])]].toMap*/
    new QMessageMapperImpl(qAdapterRegistry,receiveById)
  }
}

class QStatePartReceiverImpl(
    qAdapterRegistry: QAdapterRegistry,
    nameById: Map[Long,String],
    reducer: Reducer
) extends QStatePartReceiver {
  private def toTree(records: Iterable[QConsumerRecord]) = records.map {
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
  def receiveStateParts(records: Iterable[QConsumerRecord]): Unit = {
    val diff = toTree(records)
    //debug here
    val nextWorld = reducer.reduce(world, diff)
    synchronized{ world = nextWorld }
  }
}

class QMessageMapperImpl(
    qAdapterRegistry: QAdapterRegistry,
    receiveById: Map[TopicName, Map[Long, List[MessageMapper[Object]]]]
) extends QMessageMapper {
  def topics: List[TopicName] = receiveById.keys.toList.sortBy(_.value)
  def mapMessage(rec: QConsumerRecord): Seq[QProducerRecord] = {
    val key = qAdapterRegistry.keyAdapter.decode(rec.key)
    val valueAdapter = qAdapterRegistry.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receiveById(rec.topic).getOrElse(key.valueTypeId,Nil).flatMap(_.mapMessage(value))
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

trait QMessagesApp {
  def qAdapterRegistry: QAdapterRegistry
  def rawQSender: RawQSender
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry)
}

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry) extends QMessages {
  import qAdapterRegistry._
  def update[M](topic: TopicName, srcId: SrcId, value: M): QProducerRecord =
    inner(topic, srcId, value.getClass.asInstanceOf[Class[M]], Option(value))
  def delete[M](topic: TopicName, srcId: SrcId, cl: Class[M]): QProducerRecord =
    inner(topic, srcId, cl, None)
  private def byClass[M](cl: Class[M]): ProtoAdapter[M] with HasId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with HasId]
  private def inner[M](topic: TopicName, srcId: SrcId, cl: Class[M], value: Option[M]): QProducerRecord = {
    val valueAdapter = byClass(cl)
    val rawKey = keyAdapter.encode(QProtocol.TopicKey(srcId, valueAdapter.id))
    val rawValue = value.map(valueAdapter.encode).getOrElse(Array.empty)
    new QProducerRecord(topic, rawKey, rawValue)
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