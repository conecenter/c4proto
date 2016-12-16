
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

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.immutable.Map

trait KafkaApp extends ToStartApp {
  def bootstrapServers: String
  def qReducer: Reducer
  def qMessageMapperFactory: QMessageMapperFactory
  lazy val kafkaRawQSender: KafkaRawQSender = new KafkaRawQSender(bootstrapServers)()
  def rawQSender: RawQSender with Executable = kafkaRawQSender
  lazy val actorFactory: ActorFactory[Executable with WorldProvider] =
    new KafkaActorFactory(bootstrapServers)(qReducer, qMessageMapperFactory, kafkaRawQSender)
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
    case InboxTopicName(ActorName(n)) ⇒ s"$n.inbox"
    case StateTopicName(ActorName(n)) ⇒ s"$n.state"
  }
  def sendStart(rec: QRecord): Future[RecordMetadata] =
    producer.get.send(new ProducerRecord(topicNameToString(rec.topic), 0, rec.key, rec.value))
  def send(rec: QRecord): Unit = sendStart(rec).get()
}

////

class KafkaQConsumerRecordAdapter(topicName: TopicName, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def topic: TopicName = topicName
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
}

class KafkaActorFactory(bootstrapServers: String)(reducer: Reducer, qMessageMapperFactory: QMessageMapperFactory, kafkaRawQSender: KafkaRawQSender) extends ActorFactory[Executable with WorldProvider] {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): Executable with WorldProvider = {
    val qMessageMapper = qMessageMapperFactory.create(messageMappers)
    new KafkaActor(bootstrapServers, actorName)(reducer, qMessageMapper, kafkaRawQSender)()
  }
}

class KafkaActor(bootstrapServers: String, actorName: ActorName)(
  reducer: Reducer, qMessageMapper: QMessageMapper, rawQSender: KafkaRawQSender
)(
  alive: AtomicBoolean = new AtomicBoolean(true),
  worldFuture: CompletableFuture[AtomicReference[World]] = new CompletableFuture()
) extends Executable with WorldProvider {
  private def poll(consumer: Consumer[Array[Byte], Array[Byte]]) =
    consumer.poll(1000 /*timeout*/).asScala
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
  private def checkInterrupt() =
    if(Thread.interrupted || !alive.get) throw new InterruptedException
  private def recoverWorld(consumer: BConsumer, part: List[TopicPartition], topicName: TopicName): AtomicReference[World] = {
    val until = Single(consumer.endOffsets(part.asJava).asScala.values.toList)
    //?hang
    consumer.seekToBeginning(part.asJava)
    var currentWorld: World = Map()
    while(consumer.position(Single(part)) < until) {
      checkInterrupt()
      val recs = poll(consumer).map(new KafkaQConsumerRecordAdapter(topicName,_))
      currentWorld = reducer.reduceRecover(currentWorld, recs.toList)
    }
    new AtomicReference(currentWorld)
  }
  def run(ctx: ExecutionContext): Unit = {
    val consumer = initConsumer(ctx)
    try {
      val inboxTopicName = InboxTopicName(actorName)
      val stateTopicName = StateTopicName(actorName)
      val inboxTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(inboxTopicName), 0))
      val stateTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(stateTopicName), 0))
      consumer.assign((inboxTopicPartition ::: stateTopicPartition).asJava)
      consumer.pause(inboxTopicPartition.asJava)
      val localWorldRef = recoverWorld(consumer, stateTopicPartition, stateTopicName)
      consumer.pause(stateTopicPartition.asJava)
      consumer.resume(inboxTopicPartition.asJava)
      worldFuture.complete(localWorldRef)
      while(true){
        checkInterrupt()
        poll(consumer).toList match {
          case Nil ⇒ ()
          case rawRecs ⇒
            val recs = rawRecs.map(new KafkaQConsumerRecordAdapter(inboxTopicName,_))
            val mapping = reducer.createMessageMapping(actorName, localWorldRef.get)
            val res = (mapping /: recs)(qMessageMapper.mapMessage)
            val metadata = res.toSend.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            localWorldRef.set(res.world)
            val offset = new OffsetAndMetadata(rawRecs.last.offset + 1)
            consumer.commitSync(singletonMap(Single(inboxTopicPartition), offset))
        }
        //! if consumer.commitSync() after loop, if single fails then all recent will be re-consumed
      }
    } finally {
      consumer.close()
    }
  }
}

