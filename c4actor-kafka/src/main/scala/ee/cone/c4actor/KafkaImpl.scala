
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

class KafkaActor(bootstrapServers: String, actorName: ActorName)(
    qMessages: QMessages, reducer: Reducer, rawQSender: KafkaRawQSender, initialObservers: List[Observer]
) extends Executable {
  private def withConsumer[ST](ctx: ExecutionContext, startOffset: Long, initialState: ST)(
    receive: (ST,List[(Array[Byte],Long)])⇒Option[ST]
  ): Unit = { //ck mg
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
      "enable.auto.commit" → "false"
      //"receive.buffer.bytes" → "1000000",
      //"max.poll.records" → "10001"
      //"group.id" → actorName.value //?pos
    )
    val alive: AtomicBoolean = new AtomicBoolean(true)
    FinallyClose(new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )){ consumer ⇒
      ctx.onShutdown("Consumer",() ⇒ {
        alive.set(false)
        consumer.wakeup()
      })
      val inboxTopicName = InboxTopicName()
      val inboxTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(inboxTopicName), 0))
      println(s"server [$bootstrapServers] inbox [${rawQSender.topicNameToString(inboxTopicName)}]")
      consumer.assign(inboxTopicPartition.asJava)
      consumer.seek(Single(inboxTopicPartition),startOffset)
      iterate(initialState)
      @tailrec def iterate(state: ST): Unit = {
        if(Thread.interrupted || !alive.get) throw new InterruptedException
        val recs = consumer.poll(200 /*timeout*/).asScala.map{ rec ⇒
          (if(rec.value ne null) rec.value else Array.empty, rec.offset+1L) : (Array[Byte],Long)
        }.toList
        receive(state,recs).foreach(iterate)
      }
    }
  }

  private def startIncarnation(world:  World): (Long,World⇒Boolean) = { // cg mr
    val local = reducer.createTx(world)(Map())
    val leader = Leader(actorName.value,UUID.randomUUID.toString)
    val nLocal = LEvent.add(LEvent.update(leader)).andThen(qMessages.send)(local)
    (
      OffsetWorldKey.of(nLocal),
      world ⇒ By.srcId(classOf[Leader]).of(world).getOrElse(actorName.value,Nil).contains(leader)
    )
  }

  private def reduceRaw(world: World)(data: Array[Byte], offset: Long): Option[World] = try { // cg mr
    val updates = qMessages.toUpdates(data) ::: qMessages.offsetUpdate(offset)
    Option(reducer.reduceRecover(world, updates))
  } catch {
    case e: Exception ⇒
      e.printStackTrace()
      None // ??? exception to record
  }

  private def loadRecent[M](setup: (Array[Byte],Long)⇒Option[M]): Option[M] = // cg mg
    RawSnapshot.loadRecent.flatMap { case (offset, dataOpt) ⇒
      println(s"Loading snapshot up to $offset")
      dataOpt.flatMap(setup(_,offset))
    }.headOption

  def run(ctx: ExecutionContext): Unit = { // cg mr
    println("starting world recover...")
    val localWorldRef = new AtomicReference[World](loadRecent(reduceRaw(reducer.createWorld(Map()))).get)
    val startOffset = qMessages.worldOffset(localWorldRef.get)
    println(s"world state recovered, next offset [$startOffset]")
    val (incarnationNextOffset,checkIncarnation) = startIncarnation(localWorldRef.get)
    val observerContext = new ObserverContext(ctx, ()⇒localWorldRef.get)
    withConsumer(ctx, startOffset)(poll⇒
      Iterator.continually(poll).scanLeft(initialObservers){ (prevObservers, recs) ⇒
        for(
          (data,offset) ← recs;
          newWorld ← reduceRaw(localWorldRef.get)(data, offset)
        ) localWorldRef.set(newWorld)

        if(checkIncarnation(localWorldRef.get))
          prevObservers.flatMap(_.activate(observerContext))
        else prevObservers.map{
          case p: ProgressObserver ⇒
            val np = p.progress()
            if(p != np) println(s"loaded ${qMessages.worldOffset(localWorldRef.get)}/$incarnationNextOffset")
            np
          case o ⇒ o
        }

      }.foreach(_⇒())
    )
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