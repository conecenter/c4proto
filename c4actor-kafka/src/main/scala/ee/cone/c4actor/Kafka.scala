
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
import scala.collection.immutable.{Map, Queue}

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
  def offset: Option[Long] = Option(rec.offset)
}

class KafkaActorFactory(bootstrapServers: String)(reducer: Reducer, qMessageMapperFactory: QMessageMapperFactory, kafkaRawQSender: KafkaRawQSender) extends ActorFactory[Executable with WorldProvider] {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): Executable with WorldProvider = {
    val qMessageMapper = qMessageMapperFactory.create(messageMappers)
    new KafkaActor(bootstrapServers, actorName)(reducer, qMessageMapper, kafkaRawQSender)()
  }
}

trait WorldObserver {
  def activate(worldProvider: WorldProvider): Unit
}

class KafkaActor(bootstrapServers: String, topicName: TopicName)(
  reducer: Reducer, qMessageMapper: QMessageMapper, rawQSender: KafkaRawQSender,
  observers: List[WorldObserver]
)(
  alive: AtomicBoolean = new AtomicBoolean(true),
  worldFuture: CompletableFuture[AtomicReference[World]] = new CompletableFuture()
) extends Executable with WorldProvider {
  def world: World = worldFuture.get.get
  def run(ctx: ExecutionContext): Unit = {
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
      val topicPartition = List(new TopicPartition(rawQSender.topicNameToString(topicName), 0))
      consumer.assign(topicPartition.asJava)
      val recoverUntil = Single(consumer.endOffsets(topicPartition.asJava).asScala.values.toList)//?hang
      consumer.seekToBeginning(topicPartition.asJava)
      Iterator.continually{
        if(Thread.interrupted || !alive.get) throw new InterruptedException
        val recs = consumer.poll(200 /*timeout*/).asScala
          .map(new KafkaQConsumerRecordAdapter(topicName,_)).toList
        val recoveryMode =
          consumer.position(Single(topicPartition)) < recoverUntil
        (recs, recoveryMode)
      }.scanLeft((Queue.empty[QRecord],Map():World)){ (prev, recsMode) ⇒
        val(prevQueue,prevWorld) = prev
        val(recs, recoveryMode) = recsMode
        val queue = prevQueue.enqueue[QRecord](recs)
        if(recoveryMode || queue.isEmpty) (queue, prevWorld)
        else (Queue.empty, reducer.reduceRecover(prevWorld, queue.toList))
      }.foreach{
        case (_,world) if world.nonEmpty ⇒
          if(worldFuture.isDone) worldFuture.get.set(world)
          else worldFuture.complete(new AtomicReference(world))
          observers.foreach(_.activate(this))
        case _ ⇒ ()
      }
/*
            val mapping = reducer.createMessageMapping(topicName, localWorldRef.get)
            val res = (mapping /: recs)(qMessageMapper.mapMessage)
            val metadata = res.toSend.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            */


    } finally {
      consumer.close()
    }
  }
}


