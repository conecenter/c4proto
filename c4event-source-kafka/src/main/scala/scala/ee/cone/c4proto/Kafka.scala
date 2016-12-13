package ee.cone.c4proto

import java.lang.Long
import java.util
import java.util.Collections.singletonMap
import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4proto.Types.{Index, SrcId, World}
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

trait KafkaProducerApp extends ToStartApp {
  def bootstrapServers: String
  lazy val kafkaRawQSender: KafkaRawQSender = new KafkaRawQSender(bootstrapServers)
  def rawQSender: RawQSender with CanStart = kafkaRawQSender
  override def toStart: List[CanStart] = rawQSender :: super.toStart
}

trait KafkaActorApp extends ToStartApp {
  def bootstrapServers: String
  def serverFactory: ServerFactory
  def qMessages: QMessages
  def qReducers: Map[ActorName,Reducer]
  def treeAssembler: TreeAssembler
  def kafkaRawQSender: KafkaRawQSender
  lazy val qActors: Map[ActorName,Executable with WorldProvider] =
    qReducers.map{ case (actorName, reducer) ⇒
      actorName → new KafkaActor(bootstrapServers, actorName)(reducer, kafkaRawQSender)
    }
  private lazy val qActorServers = qActors.toList.sortBy(_._1.value).map{
    case (actorName, actor) ⇒ serverFactory.toServer(actor)
  }
  override def toStart: List[CanStart] = qActorServers ::: super.toStart
}

////

class KafkaRawQSender(bootstrapServers: String) extends RawQSender with CanStart {
  var producer: Option[Producer[Array[Byte], Array[Byte]]] = None
  def early: Option[ShouldStartEarly] = None
  def start(ctx: ExecutionContext): Unit = {
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
    ctx.onShutdown(() ⇒ producer.map(_.close()))
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

class KafkaActor(bootstrapServers: String, actorName: ActorName)(
  reducer: Reducer, rawQSender: KafkaRawQSender
) extends Executable with WorldProvider with ShouldStartEarly {
  private lazy val ready = new AtomicBoolean(false)
  private lazy val alive = new AtomicBoolean(true)
  private lazy val worldRef = new AtomicReference[World](Map())
  def isReady: Boolean = ready.get()
  private def poll(consumer: Consumer[Array[Byte], Array[Byte]]) =
      consumer.poll(1000 /*timeout*/).asScala
  def world: World = worldRef.get
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
  private def recoverWorld(consumer: BConsumer, part: List[TopicPartition], topicName: TopicName): World = {
    var until = Single(consumer.endOffsets(part.asJava).asScala.values.toList)
    //?hang
    consumer.seekToBeginning(part.asJava)
    var currentWorld: World = Map()
    while(consumer.position(Single(part)) < until) {
      checkInterrupt()
      val recs = poll(consumer).map(new KafkaQConsumerRecordAdapter(topicName,_))
      currentWorld = reducer.reduceRecover(currentWorld, recs.toList)
    }
    currentWorld
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
      worldRef.set(recoverWorld(consumer, stateTopicPartition, stateTopicName))
      consumer.pause(stateTopicPartition.asJava)
      consumer.resume(inboxTopicPartition.asJava)
      ready.set(true)
      while(true){
        checkInterrupt()
        poll(consumer).toList match {
          case Nil ⇒ ()
          case rawRecs ⇒
            val recs = rawRecs.map(new KafkaQConsumerRecordAdapter(inboxTopicName,_))
            val (nextWorld, toSend) =
              ((worldRef.get,Nil:List[QRecord]) /: recs){ (s,rec) ⇒
                val (prevWorld,prevToSend) = s
                val (world,toSend) = reducer.reduceCheck(prevWorld, rec)
                (world, toSend ::: prevToSend)
              }
            val metadata = toSend.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            worldRef.set(nextWorld)
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

