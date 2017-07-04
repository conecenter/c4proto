
package ee.cone.c4actor

import java.nio.file.Path
import java.util.Collections.singletonMap
import java.util.UUID
import java.util.concurrent.{CompletableFuture, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4actor.QProtocol.{Leader, Update, Updates}
import ee.cone.c4assemble.Single
import ee.cone.c4assemble.Types.World
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

class KafkaRawQSender(bootstrapServers: String, inboxTopicPrefix: String)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(ctx: ExecutionContext): Unit = {
    val props = Map[String, Object](
      "bootstrap.servers" → bootstrapServers,
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
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ s"$inboxTopicPrefix.inbox"
    case LogTopicName() ⇒ s"$inboxTopicPrefix.inbox.log"
    case NoTopicName ⇒ throw new Exception
  }
  private def sendStart(rec: QRecord): Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    val value = if(rec.value.nonEmpty) rec.value else null
    val topic = topicNameToString(rec.topic)
    producer.get.send(new ProducerRecord(topic, 0, rec.key, value))
  }
  def send(recs: List[QRecord]): List[Long] = {
    val futures: List[Future[RecordMetadata]] = recs.map(sendStart)
    futures.map(_.get().offset())
  }
}

////

class KafkaActor(bootstrapServers: String)(
    qMessages: QMessages, reducer: Reducer, rawQSender: KafkaRawQSender, progressObserver: Observer
) extends Executable {
  private def withConsumer[ST](ctx: ExecutionContext, startOffset: Long, initialState: List[ST])(
    receive: (List[ST],List[(Array[Byte],Long)])⇒List[ST]
  ): Unit = { //ck mg
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
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
      val inboxTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(inboxTopicName), 0))
      println(s"server [$bootstrapServers] inbox [${rawQSender.topicNameToString(inboxTopicName)}]")
      consumer.assign(inboxTopicPartition.asJava)
      consumer.seek(Single(inboxTopicPartition),startOffset)
      iteration(initialState)
      @tailrec def iteration(state: List[ST]): Unit = {
        if(Thread.interrupted || !alive.get) throw new InterruptedException
        val recs = consumer.poll(200 /*timeout*/).asScala.map{ rec ⇒
          (if(rec.value ne null) rec.value else Array.empty, rec.offset+1L) : (Array[Byte],Long)
        }.toList
        val nextState = receive(state,recs)
        if(nextState.nonEmpty) iteration(nextState)
      }
    }
  }

  private def reduceRaw(world: World)(data: Array[Byte], offset: Long): Option[World] = try { // cg mr
    val updates = qMessages.toUpdates(data) ::: qMessages.offsetUpdate(offset)
    Option(reducer.reduceRecover(world, updates))
  } catch {
    case e: Exception ⇒
      e.printStackTrace()
      None // ??? exception to record
  }



  def run(ctx: ExecutionContext): Unit = { // cg mr
    println("starting world recover...")
    val localWorldRef = new AtomicReference[World](RawSnapshot.loadRecent(reduceRaw(reducer.createWorld(Map()))).get)
    val startOffset = qMessages.worldOffset(localWorldRef.get)
    println(s"world state recovered, next offset [$startOffset]")
    val observerContext = new ObserverContext(ctx, ()⇒localWorldRef.get)
    withConsumer(ctx, startOffset, progressObserver :: Nil) { (prevObservers, recs) ⇒
        for (
          (data, offset) ← recs;
          newWorld ← reduceRaw(localWorldRef.get)(data, offset)
        ) localWorldRef.set(newWorld)
        prevObservers.flatMap(_.activate(observerContext))
    }
  }


  def r[M](ctx: ExecutionContext): Unit = {
    println("starting world recover...")
    val loadedModel = loadRecent
    println(s"world state recovered, next offset [$startOffset]")

  }

}


tailrec
load reduce observe


load until reduce
Incarnation / observe
newConsumer{
  setupConsumer
  loop
    for(poll) reduce
    observe
}closeConsumer