package ee.cone.c4proto

import java.util
import java.util.Collections
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata, OffsetCommitCallback}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.concurrent.Promise

@protocol object KafkaProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
}

class Sender(producer: KafkaProducer[Array[Byte], Array[Byte]], topic: String, findAdapter: FindAdapter) {
  def send(srcId: String, value: Object): Future[RecordMetadata] = {
    val valueAdapter = findAdapter(value)
    val key = KafkaProtocol.TopicKey(srcId,valueAdapter.id)
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

class SyncConsumer(bootstrapServers: String, groupId: String, topic: String)(
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
    while(alive.get)
      consumer.poll(1000 /*timeout*/).asScala.foreach{ rec ⇒
        handle(rec)
        consumer.commitSync(Collections.singletonMap(topicPartition, new OffsetAndMetadata(rec.offset + 1)))
      }
  } //! if consumer.commitSync() after loop, if single fails then all recent will be re-consumed
}

class ToStoredConsumer(bootstrapServers: String, topic: String, pos: Long)(
  val handle: ConsumerRecord[Array[Byte],Array[Byte]]⇒Unit
) extends Consumer {
  protected def props: Map[String, Object] = Map(
    "bootstrap.servers" → bootstrapServers,
    "enable.auto.commit" → "false"
  )
  lazy val ready: Promise[Unit] = Promise()
  protected def runInner(): Unit = {
    val topicPartition = new TopicPartition(topic, 0)
    consumer.assign(List(topicPartition).asJava)
    var readyFlag = false
    var untilPos = consumer.position(topicPartition)
    consumer.seek(topicPartition, pos)
    while(alive.get){
      if(!readyFlag && untilPos <= consumer.position(topicPartition)){
        readyFlag = true
        ready.success()
      }
      consumer.poll(1000 /*timeout*/).asScala.foreach(handle)
    }
  }
}

abstract class Consumer extends Runnable {
  protected def props: Map[String, Object]
  protected def runInner(): Unit
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
