
package ee.cone.c4actor

import java.util.concurrent.CompletableFuture

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Single
import ee.cone.c4proto.ToByteString
import okio.ByteString
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.immutable.Map

class KafkaRawQSender(conf: KafkaConfig, execution: Execution)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(): Unit = concurrent.blocking {
    val props = Map[String, Object](
      "bootstrap.servers" → conf.bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → conf.maxRequestSize,
      "compression.type" → "lz4",
      "max.request.size" → conf.maxRequestSize
      // max.request.size -- seems to be uncompressed
      // + in broker config: message.max.bytes
    )
    val serializer = new ByteArraySerializer
    producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    execution.onShutdown("Producer",() ⇒ producer.get.close())
  }

  private def sendStart(rec: QRecord): java.util.concurrent.Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    val value = if(rec.value.nonEmpty) rec.value else null
    val topic = conf.topicNameToString(rec.topic)
    producer.get.send(new ProducerRecord(topic, 0, Array.empty, value))
  }
  def send(recs: List[QRecord]): List[NextOffset] = concurrent.blocking{
    val futures: List[java.util.concurrent.Future[RecordMetadata]] = recs.map(sendStart)
    futures.map(res⇒OffsetHex(res.get().offset()+1))
  }
}

object OffsetHex {
  def apply(offset: Long): NextOffset =
    (("0" * OffsetHexSize())+java.lang.Long.toHexString(offset)).takeRight(OffsetHexSize())
}

case class KafkaConfig(bootstrapServers: String, inboxTopicPrefix: String, maxRequestSize: String)(
  ok: Unit = assert(bootstrapServers.nonEmpty && maxRequestSize.nonEmpty)
){
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ s"$inboxTopicPrefix.inbox"
    case LogTopicName() ⇒ s"$inboxTopicPrefix.inbox.log"
  }
}

case class KafkaConsuming(conf: KafkaConfig)(execution: Execution) extends Consuming with LazyLogging {
  def process[R](from: NextOffset, body: Consumer⇒R): R = {
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → conf.bootstrapServers,
      "enable.auto.commit" → "false"
      //"receive.buffer.bytes" → "1000000",
      //"max.poll.records" → "10001"
      //"group.id" → actorName.value //?pos
    )
    FinallyClose(new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )) { consumer ⇒
      execution.onShutdown("Consumer",() ⇒ consumer.wakeup()) //todo unregister
      val inboxTopicName = InboxTopicName()
      val inboxTopicPartition = List(new TopicPartition(conf.topicNameToString(inboxTopicName), 0))
      logger.info(s"server [${conf.bootstrapServers}] inbox [${conf.topicNameToString(inboxTopicName)}]")
      consumer.assign(inboxTopicPartition.asJava)
      val initialOffset = java.lang.Long.parseLong(from,16)
      consumer.seek(Single(inboxTopicPartition), initialOffset)
      body(new RKafkaConsumer(consumer, inboxTopicPartition))
    }
  }
}
//todo: remove hook

class RKafkaConsumer(
  consumer: KafkaConsumer[Array[Byte], Array[Byte]],
  inboxTopicPartition: List[TopicPartition]
) extends Consumer {
  def poll(): List[RawEvent] =
    consumer.poll(200 /*timeout*/).asScala.toList.map{ rec ⇒
      val data: Array[Byte] = if(rec.value ne null) rec.value else Array.empty
      KafkaRawEvent(OffsetHex(rec.offset+1L), ToByteString(data), rec.timestamp)
    }
  def endOffset: NextOffset =
    OffsetHex(Single(consumer.endOffsets(inboxTopicPartition.asJava).asScala.values.toList): java.lang.Long)
}

case class KafkaRawEvent(srcId: SrcId, data: ByteString, mTime: Long) extends RawEvent with MTime
