package ee.cone.c4proto

import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import ee.cone.c4proto.Types.World
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, KafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait KafkaProducerApp extends ToStartApp {
  def bootstrapServers: String
  lazy val rawQSender: RawQSender with CanStart =
    new KafkaRawQSender(bootstrapServers)
  override def toStart: List[CanStart] = rawQSender :: super.toStart
}

trait ToIdempotentConsumerApp extends ToStartApp {
  def bootstrapServers: String
  def consumerGroupId: String
  def serverFactory: ServerFactory
  def qMessageMapper: QMessageMapper
  def rawQSender: RawQSender
  lazy val toIdempotentConsumers: List[CanStart] =
    qMessageMapper.streamKeys.map(streamKey⇒
      serverFactory.toServer(new ToIdempotentConsumer(bootstrapServers, consumerGroupId, streamKey)(qMessageMapper, rawQSender))
    )
  override def toStart: List[CanStart] = toIdempotentConsumers ::: super.toStart
}

trait ToStoredConsumerApp extends ToStartApp {
  def bootstrapServers: String
  def statePartConsumerStreamKey: StreamKey
  def serverFactory: ServerFactory
  def qMessages: QMessages
  def treeAssembler: TreeAssembler
  lazy val worldProvider = new ToStoredConsumer(bootstrapServers, statePartConsumerStreamKey, 0)(qMessages,treeAssembler)
  lazy val worldServer: CanStart = serverFactory.toServer(worldProvider)
  override def toStart: List[CanStart] = worldServer :: super.toStart
}


////

class KafkaRawQSender(bootstrapServers: String) extends RawQSender with CanStart {
  var producer: Option[Producer[Array[Byte], Array[Byte]]] = None
  def early: Option[ShouldStartEarly] = None
  def start(pool: ExecutorService): Unit = {
    val props = Map[String, Object](
      "bootstrap.servers" → bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432"
    )
    val serializer = new ByteArraySerializer
    producer = Some(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    OnShutdown(() ⇒ producer.map(_.close()))
  }
  def send(rec: QRecord): Unit = {
    val streamKey = rec.streamKey
    if(streamKey.to.isEmpty) throw new Exception(s"no destination: $streamKey")
    val kRec = new ProducerRecord(streamKey.to, 0, rec.key, rec.value)
    producer.get.send(kRec).get()
  }
}

////

class KafkaQConsumerRecordAdapter(parentStreamKey: StreamKey, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def streamKey: StreamKey = parentStreamKey
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
}

//val offset = new OffsetAndMetadata(rec.offset + 1)
//consumer.commitSync(Collections.singletonMap(topicPartition, offset))

class ToIdempotentConsumer(bootstrapServers: String, groupId: String, val streamKey: StreamKey)(
  qMessageMapper: QMessageMapper, rawQSender: RawQSender
) extends KConsumer {
  protected lazy val props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false",
    "group.id" → groupId //?
  )
  protected def runInner(consumer: Consumer[Array[Byte], Array[Byte]], topicPartition: TopicPartition): Unit = {
    poll(consumer){ recs ⇒
      val toSend = recs.flatMap(qMessageMapper.mapMessage(streamKey,_)).toList
      toSend.foreach(rawQSender.send)
      consumer.commitSync()
      //! if consumer.commitSync() after loop, if single fails then all recent will be re-consumed
    }
  }
}

class ToStoredConsumer(bootstrapServers: String, val streamKey: StreamKey, pos: Long)(
  qMessages: QMessages, treeAssembler: TreeAssembler
) extends KConsumer with WorldProvider with ShouldStartEarly {
  private lazy val ready = new AtomicBoolean(false)
  def isReady: Boolean = ready.get()
  protected def props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false"
  )
  private var theWorld: World = Map()
  def world: World = synchronized{ theWorld }
  protected def runInner(consumer: Consumer[Array[Byte], Array[Byte]], topicPartition: TopicPartition): Unit = {
    var untilPos = consumer.position(topicPartition)
    //println("untilPos",untilPos)
    consumer.seek(topicPartition, pos)
    poll(consumer){ recs ⇒
      val diff = qMessages.toTree(recs)
      val nextWorld = treeAssembler.replace(world, diff)
      synchronized{ theWorld = nextWorld }
      if(!ready.get() && untilPos <= consumer.position(topicPartition) )
        ready.set(true)
    }
  }
}

abstract class KConsumer extends Runnable {
  protected def streamKey: StreamKey
  protected def props: Map[String, Object]
  protected def runInner(consumer: Consumer[Array[Byte], Array[Byte]], topicPartition: TopicPartition): Unit
  protected lazy val alive = new AtomicBoolean(true)
  protected def poll(consumer: Consumer[Array[Byte], Array[Byte]])(recv: Iterable[QRecord]⇒Unit): Unit =
    while(alive.get)
      recv(consumer.poll(1000 /*timeout*/).asScala.map(new KafkaQConsumerRecordAdapter(streamKey,_)))
  def run(): Unit = {
    val deserializer = new ByteArrayDeserializer
    val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )
    OnShutdown{() ⇒
      alive.set(false)
      consumer.wakeup()
    }
    try {
      if(streamKey.from.isEmpty) throw new Exception(s"no source: $streamKey")
      val topicPartition = new TopicPartition(streamKey.from, 0)
      consumer.assign(List(topicPartition).asJava)
      runInner(consumer, topicPartition)
    } finally {
      consumer.close()
    }
  }
}

