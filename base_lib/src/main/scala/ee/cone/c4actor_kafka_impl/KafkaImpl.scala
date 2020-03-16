
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
import ee.cone.c4di.c4
import okio.ByteString
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}

// import scala.collection.JavaConverters.{iterableAsScalaIterableConverter, mapAsJavaMapConverter, mapAsScalaMapConverter, seqAsJavaListConverter}
import scala.collection.immutable.Map
import scala.jdk.CollectionConverters.{IterableHasAsScala,MapHasAsJava,MapHasAsScala,SeqHasAsJava}

@c4("KafkaConfigApp") class ConfigKafkaConfig(config: Config, listConfig: ListConfig) extends KafkaConfig(
  bootstrapServers = config.get("C4BOOTSTRAP_SERVERS"),
  inboxTopicPrefix = config.get("C4INBOX_TOPIC_PREFIX"),
  maxRequestSize = config.get("C4MAX_REQUEST_SIZE"),
  keyStorePath = config.get("C4KEYSTORE_PATH"),
  trustStorePath = config.get("C4TRUSTSTORE_PATH"),
  keyPassPath = config.get("C4STORE_PASS_PATH"),
  extraPassPath = Single.option(listConfig.get("C4BROKER_EXTRA_PASS_PATH")),
)()

@c4("KafkaProducerApp") class KafkaRawQSender(conf: KafkaConfig, execution: Execution)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(): Unit = concurrent.blocking {
    val props = conf.ssl ++ Map[String, Object](
      "acks" -> "all",
      "retries" -> "0",
      "batch.size" -> "16384",
      "linger.ms" -> "1",
      "buffer.memory" -> conf.maxRequestSize,
      "compression.type" -> "lz4",
      "max.request.size" -> conf.maxRequestSize,
      // max.request.size -- seems to be uncompressed
      // + in broker config: message.max.bytes
    )
    val serializer = new ByteArraySerializer
    producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    execution.onShutdown("Producer",() => producer.get.close())
  }

  private def sendStart(rec: QRecord): java.util.concurrent.Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    val value: Array[Byte] = if(rec.value.nonEmpty) rec.value else null
    val topic: String = conf.topicNameToString(rec.topic)
    val headers = rec.headers.map(h => new RecordHeader(h.key, h.value.getBytes(UTF_8)).asInstanceOf[Header]).asJava
    /*val record = new ProducerRecord[Array[Byte], Array[Byte]](topic, 0, null, Array.emptyByteArray, value, headers)
    producer.get.send(record)*/
    KafkaMessageSender.send(topic, value, headers, producer)
  }
  def send(recs: List[QRecord]): List[NextOffset] = concurrent.blocking{
    val futures: List[java.util.concurrent.Future[RecordMetadata]] = recs.map(sendStart)
    futures.map(res=>OffsetHex(res.get().offset()+1))
  }
}

object OffsetHex {
  def apply(offset: Long): NextOffset =
    (("0" * OffsetHexSize())+java.lang.Long.toHexString(offset)).takeRight(OffsetHexSize())
}

case class KafkaConfig(
  bootstrapServers: String, inboxTopicPrefix: String, maxRequestSize: String,
  keyStorePath: String, trustStorePath: String, keyPassPath: String,
  extraPassPath: Option[String],
)(
  ok: Unit = assert(Seq(
    bootstrapServers, maxRequestSize,
    keyStorePath, trustStorePath, keyPassPath
  ).forall(_.nonEmpty)),
  keyPass: String = new String(Files.readAllBytes(Paths.get(keyPassPath)),UTF_8),
  extraPass: Option[String] = for(path <- extraPassPath)
    yield new String(Files.readAllBytes(Paths.get(path)),UTF_8),
){
  def ssl: Map[String,String] = Map(
    "bootstrap.servers"       -> bootstrapServers,
    "ssl.keystore.location"   -> keyStorePath,
    "ssl.keystore.password"   -> keyPass,
    "ssl.key.password"        -> keyPass,
    "ssl.truststore.location" -> trustStorePath,
    "ssl.truststore.password" -> keyPass,
    "ssl.endpoint.identification.algorithm" -> "",
  ) ++ extraPass.fold(Map(
    "security.protocol"       -> "SSL",
  ))(p=>Map(
    "security.protocol"       -> "SASL_SSL",
    "sasl.mechanism"          -> "PLAIN",
    "sasl.jaas.config"        -> s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="user" password="$p";""",
  ))
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() => s"$inboxTopicPrefix.inbox"
    //case LogTopicName() => s"$inboxTopicPrefix.inbox.log"
  }
}

@c4("KafkaConsumerApp") case class KafkaConsuming(conf: KafkaConfig)(execution: Execution) extends Consuming with LazyLogging {
  def process[R](from: NextOffset, body: Consumer=>R): R = {
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = conf.ssl ++ Map(
      "enable.auto.commit" -> "false",
      //"receive.buffer.bytes" -> "1000000",
      //"max.poll.records" -> "10001"
      //"group.id" -> actorName.value //?pos
    )
    FinallyClose(new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )) { consumer =>
      val remove = execution.onShutdown("Consumer",() => consumer.wakeup()) //todo unregister
      try {
        val inboxTopicName = InboxTopicName()
        val inboxTopicPartition = List(new TopicPartition(conf.topicNameToString(inboxTopicName), 0))
        logger.info(s"server [${conf.bootstrapServers}] inbox [${conf.topicNameToString(inboxTopicName)}] from [$from]")
        consumer.assign(inboxTopicPartition.asJava)
        val initialOffset = java.lang.Long.parseLong(from,16)
        consumer.seek(Single(inboxTopicPartition), initialOffset)
        body(new RKafkaConsumer(consumer, inboxTopicPartition))
      } finally {
        remove()
      }
    }
  }
}
//todo: remove hook

class RKafkaConsumer(
  consumer: KafkaConsumer[Array[Byte], Array[Byte]],
  inboxTopicPartition: List[TopicPartition]
) extends Consumer {
  def poll(): List[RawEvent] =
    consumer.poll(Duration.ofMillis(200) /*timeout*/).asScala.toList.map { rec: ConsumerRecord[Array[Byte], Array[Byte]] =>
      val compHeader = rec.headers().toArray.toList.map(h => RawHeader(h.key(), new String(h.value(), UTF_8)))
      val data: Array[Byte] = if (rec.value ne null) rec.value else Array.empty
      KafkaRawEvent(OffsetHex(rec.offset + 1L), ToByteString(data), compHeader, rec.timestamp)
    }
  def endOffset: NextOffset =
    OffsetHex(Single(consumer.endOffsets(inboxTopicPartition.asJava).asScala.values.toList): java.lang.Long)
}

case class KafkaRawEvent(srcId: SrcId, data: ByteString, headers: List[RawHeader], mTime: Long) extends RawEvent with MTime
