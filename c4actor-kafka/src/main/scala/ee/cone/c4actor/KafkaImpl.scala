
package ee.cone.c4actor

import java.util.concurrent.CompletableFuture

import ee.cone.c4assemble.Single
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.annotation.tailrec
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
    execution.onShutdown("Producer",() ⇒ producer.get.close())
  }

  private def sendStart(rec: QRecord): java.util.concurrent.Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    val value = if(rec.value.nonEmpty) rec.value else null
    val topic = conf.topicNameToString(rec.topic)
    producer.get.send(new ProducerRecord(topic, 0, Array.empty, value))
  }
  def send(recs: List[QRecord]): List[Long] = {
    val futures: List[java.util.concurrent.Future[RecordMetadata]] = recs.map(sendStart)
    futures.map(_.get().offset()+1)
  }
}

case class KafkaConfig(bootstrapServers: String, inboxTopicPrefix: String){
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ s"$inboxTopicPrefix.inbox"
    case LogTopicName() ⇒ s"$inboxTopicPrefix.inbox.log"
  }
}

class KafkaActor(conf: KafkaConfig)(
  rawSnapshot: RawSnapshot,
  progressObserverFactory: ProgressObserverFactory,
  execution: Execution
) extends Executable {
  def run(): Unit = concurrent.blocking { //ck mg
    val initialRawWorld = rawSnapshot.loadRecent()
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
      execution.onShutdown("Consumer",() ⇒ consumer.wakeup())
      val inboxTopicName = InboxTopicName()
      val inboxTopicPartition = List(new TopicPartition(conf.topicNameToString(inboxTopicName), 0))
      println(s"server [${conf.bootstrapServers}] inbox [${conf.topicNameToString(inboxTopicName)}]")
      consumer.assign(inboxTopicPartition.asJava)
      val endOffset: Long = Single(consumer.endOffsets(inboxTopicPartition.asJava).asScala.values.toList): java.lang.Long
      val initialRawObserver = progressObserverFactory.create(endOffset)
      consumer.seek(Single(inboxTopicPartition), initialRawWorld.offset)
      @tailrec def iteration(world: RawWorld, observer: RawObserver): Unit = {
        val reduces = consumer.poll(200 /*timeout*/).asScala.toList.map{ rec ⇒
          val data: Array[Byte] = if(rec.value ne null) rec.value else Array.empty
          val offset: Long = rec.offset+1L
          (w: RawWorld) ⇒ w.reduce(data,offset)
        }
        val newWorld = Function.chain(reduces)(world)
        val newObserver = observer.activate(newWorld)
        iteration(newWorld, newObserver)
      }
      iteration(initialRawWorld, initialRawObserver)
    }
  }
}
