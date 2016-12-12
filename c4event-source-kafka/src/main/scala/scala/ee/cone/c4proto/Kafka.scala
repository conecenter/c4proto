package ee.cone.c4proto

import java.lang.Long
import java.util
import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4proto.Types.{Index, SrcId, World}
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, KafkaConsumer}
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

trait ToIdempotentConsumerApp extends ToStartApp {
  def bootstrapServers: String
  def consumerGroupId: String
  def serverFactory: ServerFactory
  def qMessageMapper: QMessageMapper
  def kafkaRawQSender: KafkaRawQSender
  lazy val toIdempotentConsumers: List[CanStart] =
    qMessageMapper.streamKeys.map(streamKey⇒
      serverFactory.toServer(new ToIdempotentConsumer(bootstrapServers, consumerGroupId, streamKey)(qMessageMapper, kafkaRawQSender))
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
  def sendStart(rec: QRecord): Future[RecordMetadata] = {
    val streamKey = rec.streamKey
    if(streamKey.to.isEmpty) throw new Exception(s"no destination: $streamKey")
    val kRec = new ProducerRecord(streamKey.to, 0, rec.key, rec.value)
    producer.get.send(kRec)
  }
  def send(rec: QRecord): Unit = sendStart(rec).get()
}

////

class KafkaQConsumerRecordAdapter(parentStreamKey: StreamKey, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def streamKey: StreamKey = parentStreamKey
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
}





case object ErrorsKey extends WorldKey[Index[SrcId,String]](Map.empty)

class KafkaActor(bootstrapServers: String, id: String)(
  qMessages: QMessages, treeAssembler: TreeAssembler,
  qMessageMapper: QMessageMapper, rawQSender: KafkaRawQSender
) extends Executable with WorldProvider with ShouldStartEarly {
  protected def streamKey: StreamKey

  private lazy val ready = new AtomicBoolean(false)
  def isReady: Boolean = ready.get()
  protected lazy val alive = new AtomicBoolean(true)
  private def poll(consumer: Consumer[Array[Byte], Array[Byte]]) =
      consumer.poll(1000 /*timeout*/).asScala
  private val worldRef = new AtomicReference[World](Map())
  def world: World = worldRef.get
  type BConsumer = Consumer[Array[Byte], Array[Byte]]
  private def initConsumer(ctx: ExecutionContext): BConsumer = {
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
      "enable.auto.commit" → "false",
      "group.id" → id //?pos
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

  private def reduce(world: World, recs: List[QRecord]): World = {
    val diff = qMessages.toTree(recs)
    treeAssembler.replace(world, diff)
  }
  private def reduceCheck(state: (World,List[QRecord]), rec: QRecord) = try {
    val (prevWorld, prevToSend) = state
    val toSend = qMessageMapper.mapMessage(streamKey,rec).toList
    val world = reduce(prevWorld, toSend.filter(_.streamKey==streamKey))
    val errors = ErrorsKey.of(world)
    if(errors.nonEmpty) throw new Exception(errors.toString)
    (world, toSend ::: prevToSend)
  } catch {
    case e: Exception ⇒
      val (prevWorld, prevToSend) = state
      // ??? exception to record
      (prevWorld, /**/prevToSend)
  }

  private def recoverWorld(consumer: BConsumer, part: List[TopicPartition]): World = {
    var until = Single(consumer.endOffsets(part.asJava).asScala.values.toList)
    //?hang
    consumer.seekToBeginning(part.asJava)
    var currentWorld: World = Map()
    while(consumer.position(Single(part)) < until) {
      checkInterrupt()
      currentWorld = reduce(currentWorld, poll(consumer).map(new KafkaQConsumerRecordAdapter(streamKey,_)).toList)
    }
    currentWorld
  }
  def run(ctx: ExecutionContext): Unit = {
    val consumer = initConsumer(ctx)
    try {
      val inboxTopicPartition = List(new TopicPartition(s"$id.inbox", 0))
      val stateTopicPartition = List(new TopicPartition(s"$id.state", 0))
      consumer.assign((inboxTopicPartition ::: stateTopicPartition).asJava)
      consumer.pause(inboxTopicPartition.asJava)
      worldRef.set(recoverWorld(consumer, stateTopicPartition))
      consumer.pause(stateTopicPartition.asJava)
      consumer.resume(inboxTopicPartition.asJava)
      ready.set(true)
      while(true){
        checkInterrupt()
        poll(consumer).toList match {
          case Nil ⇒ ()
          case recs ⇒
            .map(new KafkaQConsumerRecordAdapter(streamKey,_))

            val (nextWorld, toSend) = ((worldRef.get,Nil:List[QRecord]) /: recs)(reduceCheck)
            val metadata = toSend.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            worldRef.set(nextWorld)
            val offset = new OffsetAndMetadata(recs.last.offset + 1)
        }


        consumer.commitSync()


        //consumer.commitSync(Collections.singletonMap(topicPartition, offset))

        //! if consumer.commitSync() after loop, if single fails then all recent will be re-consumed
      }
    } finally {
      consumer.close()
    }
  }
}

