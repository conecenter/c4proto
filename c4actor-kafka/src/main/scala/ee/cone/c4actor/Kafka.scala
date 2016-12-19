
package ee.cone.c4actor

import java.util.concurrent.{CompletableFuture, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4actor.Types.World
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.immutable.{Map, Queue}

trait KafkaApp extends ToStartApp {
  def bootstrapServers: String
  def qReducer: Reducer
  def qMessageMapperFactory: QMessageMapperFactory
  def worldTopicName: TopicName
  def initialWorldObservers: List[WorldObserver]
  lazy val kafkaRawQSender: KafkaRawQSender = new KafkaRawQSender(bootstrapServers)()
  def rawQSender: RawQSender with Executable = kafkaRawQSender
  lazy val observableWorld: Executable =
    new KafkaActor(bootstrapServers, worldTopicName)(qReducer, initialWorldObservers)
  override def toStart: List[Executable] = observableWorld :: rawQSender :: super.toStart
}

////

class KafkaRawQSender(bootstrapServers: String)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(ctx: ExecutionContext): Unit = {
    val props = Map[String, Object](
      "bootstrap.servers" → bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432"
    )
    val serializer = new ByteArraySerializer
    producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    ctx.onShutdown(() ⇒ producer.get.close())
  }
  def sendStart(rec: QRecord): Future[RecordMetadata] =
    producer.get.send(new ProducerRecord(rec.topic.value, 0, rec.key, rec.value))
  def send(rec: QRecord): Unit = sendStart(rec).get()
}

////

class KafkaQConsumerRecordAdapter(topicName: TopicName, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def topic: TopicName = topicName
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
  def offset: Option[Long] = Option(rec.offset)
}

class KafkaActor(bootstrapServers: String, topicName: TopicName)(
  reducer: Reducer, initialObservers: List[WorldObserver]
) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    val alive: AtomicBoolean = new AtomicBoolean(true)
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
      "enable.auto.commit" → "false",
      "group.id" → topicName.value //?pos
    )
    val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )
    ctx.onShutdown{() ⇒
      alive.set(false)
      consumer.wakeup()
    }
    try {
      val topicPartition = List(new TopicPartition(topicName.value, 0))
      consumer.assign(topicPartition.asJava)
      val recoverUntil = Single(consumer.endOffsets(topicPartition.asJava).asScala.values.toList)//?hang
      consumer.seekToBeginning(topicPartition.asJava)
      val worldRef = new AtomicReference[World](Map())
      Iterator.continually{
        if(Thread.interrupted || !alive.get) throw new InterruptedException
        val recs = consumer.poll(200 /*timeout*/).asScala
          .map(new KafkaQConsumerRecordAdapter(topicName,_)).toList
        val recoveryMode =
          consumer.position(Single(topicPartition)) < recoverUntil
        (recs, recoveryMode)
      }.scanLeft((Queue.empty[QRecord],initialObservers)){ (prev, recsMode) ⇒
        val(prevQueue,prevObservers) = prev
        val(recs, recoveryMode) = recsMode
        val queue = prevQueue.enqueue[QRecord](recs)
        if(recoveryMode) (queue, prevObservers) // || queue.isEmpty
        else {
          worldRef.set(reducer.reduceRecover(worldRef.get, queue.toList))
          val observers = prevObservers.flatMap(_.activate(()⇒worldRef.get))
          (Queue.empty, observers)
        }
      }.foreach(_⇒())
    } finally {
      consumer.close()
    }
  }
}


