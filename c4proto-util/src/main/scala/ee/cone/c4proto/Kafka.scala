package ee.cone.c4proto

import java.util.Collections
import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter


@protocol object KafkaProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
}

object Pool {
  def apply(): ExecutorService = {
    val pool: ExecutorService = Executors.newCachedThreadPool() //newWorkStealingPool
    OnShutdown(()⇒{
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    pool
  }
}

class Handling[R](findAdapter: FindAdapter, val byId: Map[Long, Object ⇒ R]=Map[Long, Object ⇒ R]()) {
  def add[M](cl: Class[M])(handle: M⇒R): Handling[R] =
    new Handling[R](findAdapter, byId + (
      findAdapter.byClass(cl).id → handle.asInstanceOf[Object ⇒ R]
      ))
}

class Sender(
  producer: KafkaProducer[Array[Byte], Array[Byte]],
  topic: String,
  findAdapter: FindAdapter,
  toSrcId: Handling[String]
) {
  def send(value: Object): Future[RecordMetadata] = {
    val valueAdapter = findAdapter(value)
    val srcId = toSrcId.byId(valueAdapter.id)(value)
    val key = KafkaProtocol.TopicKey(srcId, valueAdapter.id)
    val keyAdapter = findAdapter(key)
    val rawKey = keyAdapter.encode(key)
    val rawValue = valueAdapter.encode(value)
    producer.send(new ProducerRecord(topic, rawKey, rawValue))
  }
}

object Producer {
  def apply(bootstrapServers: String): KafkaProducer[Array[Byte], Array[Byte]] = {
    val props = Map[String, Object](
      "bootstrap.servers" → bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432"
    )
    val serializer = new ByteArraySerializer
    val producer = new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    )
    OnShutdown(() ⇒ producer.close())
    producer
  }
}

class Receiver(findAdapter: FindAdapter, receive: Handling[Unit]){
  def receive(rec: ConsumerRecord[Array[Byte], Array[Byte]]): Unit = {
    val keyAdapter = findAdapter.byClass(classOf[KafkaProtocol.TopicKey])
    val key = keyAdapter.decode(rec.key)
    val valueAdapter = findAdapter.byId(key.valueTypeId)
    val value = valueAdapter.decode(rec.value)
    receive.byId(key.valueTypeId)(value)
    //decode(new ProtoReader(new okio.Buffer().write(bytes)))
  }
}




class ToIdempotentConsumer(bootstrapServers: String, groupId: String, topic: String)(
  val pool: ExecutorService,
  val handle: ConsumerRecord[Array[Byte],Array[Byte]]⇒Unit
) extends Consumer {
  protected lazy val props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false",
    "group.id" → groupId //?
  )
  protected def runInner(): Unit = {
    val topicPartition = new TopicPartition(topic, 0)
    consumer.assign(List(topicPartition).asJava)
    while(alive.get) {
      consumer.poll(1000 /*timeout*/).asScala.foreach { rec ⇒
        handle(rec)
        val offset = new OffsetAndMetadata(rec.offset + 1)
        //consumer.commitSync(Collections.singletonMap(topicPartition, offset))
      }
      consumer.commitSync()
      //! if consumer.commitSync() after loop, if single fails then all recent will be re-consumed
    }
  }
  protected def readyState: ConsumerState = Started
}



class ToStoredConsumer(bootstrapServers: String, topic: String, pos: Long)(
  val pool: ExecutorService,
  val handle: ConsumerRecord[Array[Byte],Array[Byte]]⇒Unit
) extends Consumer {
  private lazy val ready = new AtomicBoolean(false)
  protected def readyState: ConsumerState = if(ready.get()) Started else Starting
  protected def props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false"
  )
  protected def runInner(): Unit = {
    val topicPartition = new TopicPartition(topic, 0)
    consumer.assign(List(topicPartition).asJava)
    var untilPos = consumer.position(topicPartition)
    //println("untilPos",untilPos)
    consumer.seek(topicPartition, pos)
    while(alive.get){
      if(!ready.get() && untilPos <= consumer.position(topicPartition))
        ready.set(true)
      consumer.poll(1000 /*timeout*/).asScala.foreach(handle)
    }
  }
}

sealed trait ConsumerState
case object NotStarted extends ConsumerState
case object Starting extends ConsumerState
case object Started extends ConsumerState
case object Finished extends ConsumerState
abstract class Consumer extends Runnable {
  protected def props: Map[String, Object]
  protected def runInner(): Unit
  protected def pool: ExecutorService
  protected def readyState: ConsumerState
  private var future: Option[Future[_]] = None
  def start(): Unit = synchronized{
    future = Option(pool.submit(this))
  }
  def state: ConsumerState = synchronized {
    future.map(_.isDone) match {
      case None ⇒ NotStarted
      case Some(false) ⇒ readyState
      case Some(true) ⇒ Finished
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
