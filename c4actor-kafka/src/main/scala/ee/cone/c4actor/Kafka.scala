
package ee.cone.c4actor

import java.util.Collections.singletonMap
import java.util.concurrent.{CompletableFuture, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4actor.Types.World
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.annotation.tailrec
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.immutable.{Map, Queue}

trait KafkaApp extends ToStartApp {
  def bootstrapServers: String
  def qReducer: Reducer
  lazy val kafkaRawQSender: KafkaRawQSender = new KafkaRawQSender(bootstrapServers)()
  def rawQSender: RawQSender with Executable = kafkaRawQSender
  lazy val actorFactory: ActorFactory[Executable with WorldProvider] =
    new KafkaActorFactory(bootstrapServers)(qReducer, kafkaRawQSender)
  override def toStart: List[Executable] = rawQSender :: super.toStart
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
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ "inbox"
    case StateTopicName(ActorName(n)) ⇒ s"$n.state"
  }
  def sendStart(rec: QRecord): Future[RecordMetadata] =
    producer.get.send(new ProducerRecord(topicNameToString(rec.topic), 0, rec.key, rec.value))
  def send(rec: QRecord): Long = sendStart(rec).get().offset()
}

////

class KafkaQConsumerRecordAdapter(topicName: TopicName, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def topic: TopicName = topicName
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
  def offset: Option[Long] = Option(rec.offset)
}

class KafkaActorFactory(bootstrapServers: String)(reducer: Reducer, kafkaRawQSender: KafkaRawQSender) extends ActorFactory[Executable with WorldProvider] {
  def create(actorName: ActorName, observer: Observer): Executable with WorldProvider = {
    new KafkaActor(bootstrapServers, actorName)(reducer, kafkaRawQSender)()
  }
}

class KafkaActor(bootstrapServers: String, actorName: ActorName)(
  reducer: Reducer, rawQSender: KafkaRawQSender, initialObserver: Observer
)(
  alive: AtomicBoolean = new AtomicBoolean(true),
  worldFuture: CompletableFuture[AtomicReference[World]] = new CompletableFuture()
) extends Executable with WorldProvider {
  private def iterator(consumer: Consumer[Array[Byte], Array[Byte]]) = Iterator.continually{
    if(Thread.interrupted || !alive.get) throw new InterruptedException
    consumer.poll(200 /*timeout*/).asScala
      .map(new KafkaQConsumerRecordAdapter(NoTopicName, _)).toList
  }
  def world: World = worldFuture.get.get
  type BConsumer = Consumer[Array[Byte], Array[Byte]]
  private def initConsumer(ctx: ExecutionContext): BConsumer = {
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
      "enable.auto.commit" → "false",
      "group.id" → actorName.value //?pos
    )
    val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )
    ctx.onShutdown{() ⇒
      alive.set(false)
      consumer.wakeup()
    }
    consumer
  }
  private def recoverWorld(consumer: BConsumer, part: List[TopicPartition], topicName: TopicName): AtomicReference[World] = {
    val until = Single(consumer.endOffsets(part.asJava).asScala.values.toList)
    //?hang
    consumer.seekToBeginning(part.asJava)
    val recsIterator = iterator(consumer).flatten
    @tailrec def toQueue(queue: Queue[QRecord] = Queue.empty): Queue[QRecord] = {
      val rec = recsIterator.next()
      if(rec.offset.get + 1 >= until) queue.enqueue(rec) else toQueue(queue.enqueue(rec))
    }
    new AtomicReference(reducer.reduceRecover(Map(), toQueue().toList))
  }
  def run(ctx: ExecutionContext): Unit = {
    val consumer = initConsumer(ctx)
    try {
      val inboxTopicName = InboxTopicName()
      val stateTopicName = StateTopicName(actorName)
      val inboxTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(inboxTopicName), 0))
      val stateTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(stateTopicName), 0))
      consumer.assign((inboxTopicPartition ::: stateTopicPartition).asJava)
      consumer.pause(inboxTopicPartition.asJava)
      val localWorldRef = recoverWorld(consumer, stateTopicPartition, stateTopicName)
      consumer.pause(stateTopicPartition.asJava)
      consumer.resume(inboxTopicPartition.asJava)
      worldFuture.complete(localWorldRef)
      iterator(consumer).scanLeft(Seq(initialObserver)){ (prevObservers, recs) ⇒
        recs match {
          case Nil ⇒ ()
          case inboxRecs ⇒
            val(world,queue) = reducer.reduceReceive(actorName, localWorldRef.get, inboxRecs)
            val metadata = queue.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            val offset: java.lang.Long = inboxRecs.last.offset.get + 1
            localWorldRef.set(world + (OffsetWorldKey → offset))
            consumer.commitSync(singletonMap(Single(inboxTopicPartition), new OffsetAndMetadata(offset)))
        }
        prevObservers.flatMap(_.activate(()⇒reducer.createTx(localWorldRef.get)))
      }.foreach(_⇒())
    } finally {
      consumer.close()
    }
  }
}

