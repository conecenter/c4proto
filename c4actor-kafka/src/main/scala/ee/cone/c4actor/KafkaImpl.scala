
package ee.cone.c4actor

import java.util.concurrent.{CompletableFuture, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4assemble.Single
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

class KafkaRawQSender(conf: KafkaConfig)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(ctx: ExecutionContext): Unit = {
    val props = Map[String, Object](
      "bootstrap.servers" → conf.bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432",
      "compression.type" → "lz4",
      "max.request.size" → "10000000"
      // max.request.size -- seems to be uncompressed
      // + in broker config: message.max.bytes
    )
    val serializer = new ByteArraySerializer
    producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    ctx.onShutdown("Producer",() ⇒ producer.get.close())
  }

  private def sendStart(rec: QRecord): Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    val value = if(rec.value.nonEmpty) rec.value else null
    val topic = conf.topicNameToString(rec.topic)
    producer.get.send(new ProducerRecord(topic, 0, Array.empty, value))
  }
  def send(recs: List[QRecord]): List[Long] = {
    val futures: List[Future[RecordMetadata]] = recs.map(sendStart)
    futures.map(_.get().offset())
  }
}

case class KafkaConfig(bootstrapServers: String, inboxTopicPrefix: String){
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ s"$inboxTopicPrefix.inbox"
    case LogTopicName() ⇒ s"$inboxTopicPrefix.inbox.log"
    case NoTopicName ⇒ throw new Exception
  }
}

class KafkaActor(conf: KafkaConfig)(
  rawSnapshot: RawSnapshot, initialRawObserver: RawObserver
) extends Executable {
  def run(ctx: ExecutionContext): Unit = { //ck mg
    val observer = new AtomicReference[RawObserver](initialRawObserver)
    observer.set(rawSnapshot.loadRecent(observer.get))
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
    )){ consumer ⇒
      val alive: AtomicBoolean = new AtomicBoolean(true)
      ctx.onShutdown("Consumer",() ⇒ {
        alive.set(false)
        consumer.wakeup()
      })
      val inboxTopicName = InboxTopicName()
      val inboxTopicPartition = List(new TopicPartition(conf.topicNameToString(inboxTopicName), 0))
      println(s"server [${conf.bootstrapServers}] inbox [${conf.topicNameToString(inboxTopicName)}]")
      consumer.assign(inboxTopicPartition.asJava)
      consumer.seek(Single(inboxTopicPartition),observer.get.offset)
      val endOffset: Long = Single(consumer.endOffsets(inboxTopicPartition.asJava).asScala.values.toList)
      while(observer.get.isActive){
        if(Thread.interrupted || !alive.get) throw new InterruptedException
        for(rec ← consumer.poll(200 /*timeout*/).asScala){
          val data: Array[Byte] = if(rec.value ne null) rec.value else Array.empty
          val offset: Long = rec.offset+1L
          observer.set(observer.get.reduce(data,offset))
        }
        observer.set(observer.get.activate(()⇒observer.get, endOffset))
      }
      // exit?
    }
  }
}
