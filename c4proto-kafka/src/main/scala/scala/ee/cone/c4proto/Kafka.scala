package ee.cone.c4proto

import java.util.Collections
import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter


object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
}

trait PoolApp {
  lazy val pool: Pool = new Pool
}

class Pool {
  var value: Option[ExecutorService] = None
  def start(): Unit = {
    val pool = Executors.newCachedThreadPool() //newWorkStealingPool
    OnShutdown(()⇒{
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    value = Some(pool)
  }
}

////

trait KafkaProducerApp extends ToStartApp {
  def bootstrapServers: String
  lazy val rawQSender: RawQSender with CanStart =
    new KafkaRawQSender(bootstrapServers)
  override def toStart: List[CanStart] = rawQSender :: super.toStart
}

class KafkaRawQSender(bootstrapServers: String) extends RawQSender with CanStart {
  var producer: Option[Producer[Array[Byte], Array[Byte]]] = None
  def start(): Unit = {
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
  def send(topic: TopicName, key: Array[Byte], value: Array[Byte]): Unit = {
    producer.get.send(new ProducerRecord(topic.value, 0, key, value)).get()
  }
}

////

class KafkaQRecordAdapter(rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
  def offset: Long = rec.offset
}

trait ToIdempotentConsumerApp extends ToStartApp {
  def bootstrapServers: String
  def messageConsumerTopic: TopicName
  def consumerGroupId: String
  def pool: Pool
  def qMessageReceiver: QMessageReceiver
  lazy val toIdempotentConsumer = new ToIdempotentConsumer(bootstrapServers, consumerGroupId, messageConsumerTopic)(pool, qMessageReceiver)
  override def toStart: List[CanStart] = toIdempotentConsumer :: super.toStart
}

class ToIdempotentConsumer(bootstrapServers: String, groupId: String, topic: TopicName)(
  val pool: Pool, qMessageReceiver: QMessageReceiver
) extends Consumer {
  protected lazy val props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false",
    "group.id" → groupId //?
  )
  protected def runInner(): Unit = {
    val topicPartition = new TopicPartition(topic.value, 0)
    consumer.assign(List(topicPartition).asJava)
    while(alive.get) {
      consumer.poll(1000 /*timeout*/).asScala.foreach { rec ⇒
        qMessageReceiver.receiveMessage(new KafkaQRecordAdapter(rec))
        //val offset = new OffsetAndMetadata(rec.offset + 1)
        //consumer.commitSync(Collections.singletonMap(topicPartition, offset))
      }
      consumer.commitSync()
      //! if consumer.commitSync() after loop, if single fails then all recent will be re-consumed
    }
  }
  protected def readyState: Int = ConsumerState.started
}

trait ToStoredConsumerApp {
  def bootstrapServers: String
  def statePartConsumerTopic: TopicName
  def pool: Pool
  def qStatePartReceiver: QStatePartReceiver
  lazy val toStoredConsumer = new ToStoredConsumer(bootstrapServers, statePartConsumerTopic, 0)(pool,qStatePartReceiver)
  // to start before others
}

class ToStoredConsumer(bootstrapServers: String, topic: TopicName, pos: Long)(
  val pool: Pool, qStatePartReceiver: QStatePartReceiver
) extends Consumer {
  private lazy val ready = new AtomicBoolean(false)
  protected def readyState: Int =
    if(ready.get()) ConsumerState.started else ConsumerState.starting
  protected def props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false"
  )
  protected def runInner(): Unit = {
    val topicPartition = new TopicPartition(topic.value, 0)
    consumer.assign(List(topicPartition).asJava)
    var untilPos = consumer.position(topicPartition)
    //println("untilPos",untilPos)
    consumer.seek(topicPartition, pos)
    while(alive.get){
      if(!ready.get() && untilPos <= consumer.position(topicPartition))
        ready.set(true)
      val records = consumer.poll(1000 /*timeout*/).asScala
        .map(new KafkaQRecordAdapter(_))
      qStatePartReceiver.receiveStateParts(records)
    }
  }
}

object ConsumerState {
  def notStarted = 0
  def starting = 1
  def started = 2
  def finished = 3
}
abstract class Consumer extends Runnable with CanStart {
  protected def props: Map[String, Object]
  protected def runInner(): Unit
  protected def pool: Pool
  protected def readyState: Int
  private var future: Option[Future[_]] = None
  def start(): Unit = synchronized{
    future = Option(pool.value.get.submit(this))
  }
  def state: Int = synchronized {
    future.map(_.isDone) match {
      case None ⇒ ConsumerState.notStarted
      case Some(false) ⇒ readyState
      case Some(true) ⇒ ConsumerState.finished
    }
  }
  private lazy val deserializer = new ByteArrayDeserializer
  protected lazy val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
    props.asJava, deserializer, deserializer
  )
  protected lazy val alive = new AtomicBoolean(true)
  def run(): Unit = {
    try {
      OnShutdown{() ⇒
        alive.set(false)
        consumer.wakeup()
      }
      runInner()
    } finally {
      consumer.close()
    }
  }
}

