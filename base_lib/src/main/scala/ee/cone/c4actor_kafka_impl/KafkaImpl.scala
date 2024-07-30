
package ee.cone.c4actor_kafka_impl

import java.time.Duration
import java.util.concurrent.CompletableFuture
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Single
import ee.cone.c4proto.ToByteString

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import ee.cone.c4actor._
import ee.cone.c4di.{c4, c4multi, provide}
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}

import scala.jdk.CollectionConverters.{IterableHasAsScala,MapHasAsJava,MapHasAsScala,SeqHasAsJava}

@c4("KafkaProducerApp") final class KafkaRawQSenderProvider(
  factory: KafkaRawQSenderFactory, disable: List[DisableDefProducer]
)(
  res: Seq[RawQSender with RawQSenderExecutable] = if(disable.nonEmpty) Nil else Seq(factory.create())
){
  @provide def senders: Seq[RawQSender] = res
  @provide def executables: Seq[RawQSenderExecutable] = res
}

@c4multi("KafkaProducerApp") final class KafkaRawQSender()(
  conf: KafkaConfig, execution: Execution,
  loBroker: LOBroker, listConfig: ListConfig,
)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] =
    new CompletableFuture(),
  minLOSize: Long =
    Single.option(listConfig.get("C4BROKER_MIN_LO_SIZE")).fold(1000000L)(_.toLong) // default max.request.size is 1048576
) extends RawQSender with RawQSenderExecutable with LazyLogging {
  def run(): Unit = concurrent.blocking {
    val props = conf.ssl ++ Map[String, Object](
      "acks" -> "all",
      "retries" -> "0",
      "batch.size" -> "16384",
      "linger.ms" -> "1",
      "compression.type" -> "lz4",
    )
    val serializer = new ByteArraySerializer
    assert(producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    )))
    val remove = execution.onShutdown("Producer",() => producer.get.close()) // we'll not remove hook
  }

  private def sendStart(rec: QRecord): java.util.concurrent.Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    @SuppressWarnings(Array("org.wartremover.warts.Null")) val value: Array[Byte] = if(rec.value.nonEmpty) rec.value else null //why null?
    val topic: String = conf.topicNameToString(rec.topic)
    val headers = rec.headers.map(h => new RecordHeader(h.key, h.value.getBytes(UTF_8)).asInstanceOf[Header]).asJava
    /*val record = new ProducerRecord[Array[Byte], Array[Byte]](topic, 0, null, Array.emptyByteArray, value, headers)
    producer.get.send(record)*/
    KafkaMessageSender.send(topic, value, headers, producer)
  }
  def send(rec: QRecord): NextOffset = {
    val isLO = rec.value.length >= minLOSize
    val rRec = if(isLO) loBroker.put(rec) else rec
    val future: java.util.concurrent.Future[RecordMetadata] = sendStart(rRec)
    val offset = OffsetHex(future.get().offset()+1)
    if(isLO) logger.debug(s"LO $offset")
    offset
  }
}

object OffsetHex {
  def apply(offset: Long): NextOffset =
    (("0" * OffsetHexSize())+java.lang.Long.toHexString(offset)).takeRight(OffsetHexSize())
}

@c4("KafkaConfigApp") final case class KafkaConfig()(config: Config)(
  val ssl: Map[String,String] = {
    val line = config.get("C4KAFKA_CONFIG")
    line.split(line.substring(0,1),-1).tail.grouped(3)
      .map{ case Array("C",k,v) => (k,v) case Array("E",k,v) => (k,Files.readString(Paths.get(config.get(v)))) }.toMap
  }
){
  def topicNameToString(name: TxLogName): String = s"${name.value}.inbox"
}

@c4("KafkaConsumerApp") final class ConsumerBeginningOffsetImpl(consuming: Consuming) extends ConsumerBeginningOffset {
  def get(): NextOffset = consuming.process("0" * OffsetHexSize(), { case c: RKafkaConsumer => c.beginningOffset })
}

@c4("KafkaConsumerApp") final class KafkaConsumingProvider(
  disable: Option[DisableDefConsuming], factory: KafkaConsumingFactory,
  conf: KafkaConfig, currentTxLogName: CurrentTxLogName,
){
  @provide def consumings: Seq[Consuming] = if(disable.nonEmpty) Nil else Seq(factory.create(conf, currentTxLogName))
}

@c4multi("KafkaConsumerApp") final case class KafkaConsuming(
  conf: KafkaConfig, currentTxLogName: CurrentTxLogName,
)(
  execution: Execution, consumerFactory: RKafkaConsumerFactory,
) extends Consuming with LazyLogging {
  def process[R](from: NextOffset, body: Consumer=>R): R =
    process(List(currentTxLogName->from), body)
  def process[R](from: List[(TxLogName,NextOffset)], body: Consumer=>R): R = { // conf.topicNameToString
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = conf.ssl ++ Map(
      "enable.auto.commit" -> "false",
    )
    FinallyClose(new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )) { consumer =>
      val remove = execution.onShutdown("Consumer",() => consumer.wakeup()) //todo unregister
      try {
        val fromList = from.map{
          case (name,offset) =>
            val topicName = conf.topicNameToString(name)
            val partition = new TopicPartition(topicName, 0)
            ((topicName->name),partition,offset)
        }
        val topicNameMap = fromList.map{ case(kv,_,_)=> kv }.toMap
        val topicPartitions = fromList.map{ case(_,topicPartition,_)=> topicPartition }
        consumer.assign(topicPartitions.asJava)
        for((_,topicPartition,offset)<-fromList){
          logger.info(s"from topic [${topicPartition.topic}] from offset [$offset]")
          val initialOffset = java.lang.Long.parseLong(offset,16)
          consumer.seek(topicPartition, initialOffset)
        }
        body(consumerFactory.create(consumer, topicPartitions, topicNameMap))
      } finally {
        remove()
      }
    }
  }
}
//todo: remove hook

@c4multi("KafkaConsumerApp") final class RKafkaConsumer(
  consumer: KafkaConsumer[Array[Byte], Array[Byte]],
  inboxTopicPartition: List[TopicPartition],
  topicNameMap: Map[String,TxLogName],
)(loBroker: LOBroker) extends Consumer with LazyLogging {
  def poll(): List[RawEvent] = {
    val records = consumer.poll(Duration.ofMillis(200) /*timeout*/).asScala.toList
    val events = loBroker.get(records.map { rec: ConsumerRecord[Array[Byte], Array[Byte]] =>
      val compHeader = rec.headers().toArray.toList.map(h => RawHeader(h.key(), new String(h.value(), UTF_8)))
      val data: Array[Byte] = if (rec.value ne null) rec.value else Array.empty
      ExtendedRawEvent(OffsetHex(rec.offset + 1L), ToByteString(data), compHeader, topicNameMap(rec.topic))
    })
    logger.debug(events.map(_.srcId).mkString(" "))
    if (records.nonEmpty) {
      val latency = System.currentTimeMillis - records.map(_.timestamp).min //check rec.timestampType == TimestampType.CREATE_TIME ?
      logger.debug(s"p-c latency $latency ms")
    }
    events
  }

  def endOffset: NextOffset = // may be extend to endOffset-s
    OffsetHex(Single(consumer.endOffsets(inboxTopicPartition.asJava).asScala.values.toList): java.lang.Long)
  def beginningOffset: NextOffset =
    OffsetHex(Single(consumer.beginningOffsets(inboxTopicPartition.asJava).asScala.values.toList): java.lang.Long)
}
