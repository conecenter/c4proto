package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4assemble.Single
import ee.cone.c4di.{c4, c4multi}
import okio.ByteString

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.collection.mutable

/*
Large record (LO) goes to the same log as parts, not depending on log implementation:
  parts with header c=lo-part are started together, and all are awaited;
  then the last record with header c=lo and body "<part offsets>:<orig header key>:<value>:...";
so the last record exists only if all parts are written.
Consumer caches parts (C4LO_CACHE_SIZE) and on the last record emits the whole one with its offset;
parts missing in cache (started from snapshot in the middle of LO, or evicted) are read again.
Nothing above Consumer sees parts.
Old consumers fail such records (unknown c), so deploy producers after consumers.
*/

object LO {
  val cKey = "c" // same key as compression, so old consumers fail loudly instead of decoding a part
  val partValue = "lo-part"
  val lastValue = "lo"
}

@c4("LOPartApp") final class LOChunkerRawQSenderImpl(
  rawPartQSenders: List[RawPartQSender], listConfig: ListConfig,
)(
  size: Int = Single.option(listConfig.get("C4LO_CHUNK_SIZE")).fold(900_000)(_.toInt),
) extends RawQSender {
  import LO._
  def send(rec: QRecord): NextOffset = {
    val sender = Single(rawPartQSenders)
    if (rec.value.length <= size) sender.sendStart(rec)() else {
      val partHeaders = List(RawHeader(cKey, partValue))
      val parts = rec.value.grouped(size).toList.map(part => sender.sendStart(QRecord(rec.topic, part, partHeaders)))
      val partOffsets = parts.map(_()) // all started, then awaited
      val body = (partOffsets.mkString(",") :: rec.headers.toList.flatMap(h => List(h.key, h.value))).mkString(":")
      sender.sendStart(QRecord(rec.topic, body.getBytes(UTF_8), List(RawHeader(cKey, lastValue))))()
    }
  }
}

@c4("LOPartApp") final class LOConsuming(factory: LOConsumerFactory, inner: PartConsuming) extends Consuming {
  def process[R](from: NextOffset, body: Consumer => R): R =
    inner.process(from, consumer => body(factory.create(consumer)))
}

// one per consumer, used from its thread
@c4multi("LOPartApp") final class LOConsumer(consumer: Consumer)(
  consuming: PartConsuming, listConfig: ListConfig
) extends Consumer with LazyLogging {
  import LO._
  private val maxSize: Int = Single.option(listConfig.get("C4LO_CACHE_SIZE")).fold(256)(_.toInt)
  private val cache = mutable.LinkedHashMap.empty[NextOffset, ByteString] // part offset -> data
  def endOffset: NextOffset = consumer.endOffset
  def poll(): List[RawEvent] = consumer.poll().flatMap { ev =>
    header(ev) match {
      case Some(`partValue`) =>
        cache.update(ev.srcId, ev.data)
        if (cache.size > maxSize) ignoreRemoved(cache.remove(cache.head._1).get)
        Nil
      case Some(`lastValue`) => List(assemble(ev))
      case _ => List(ev)
    }
  }
  private def ignoreRemoved(value: ByteString): Unit = ()
  private def header(ev: RawEvent): Option[String] = ev.headers.collectFirst { case h if h.key == cKey => h.value }
  private def prev(offset: NextOffset): NextOffset = // position to read the record with this srcId from
    (("0" * OffsetHexSize()) + java.lang.Long.toHexString(java.lang.Long.parseLong(offset, 16) - 1L))
      .takeRight(OffsetHexSize())
  private def assemble(ev: RawEvent): RawEvent = {
    val partOffsetsStr :: kvs = ev.data.utf8().split(":", -1).toList
    val partOffsets = partOffsetsStr.split(',').toList
    val headers = kvs.grouped(2).map {
      case k :: v :: Nil => RawHeader(k, v)
      case e => throw new Exception(e.toString)
    }.toList;
    {
      val partOffsetsSet = partOffsets.toSet
      val max = partOffsets.max
      def hasMissing = !partOffsets.forall(cache.contains)
      @tailrec def readAgainIter(consumer: Consumer): Unit = {
        logger.info(s"LO ${ev.srcId}: reading missing parts again")
        val events = consumer.poll()
        for (e <- events if partOffsetsSet(e.srcId) && header(e).contains(partValue)) cache.update(e.srcId, e.data)
        if (!hasMissing) ()
        else if (events.exists(_.srcId > max)) throw new Exception(s"LO ${ev.srcId}: parts not found")
        else readAgainIter(consumer)
      }
      if (hasMissing) consuming.process(prev(partOffsets.min), readAgainIter)
    };
    val data = {
      val out = new okio.Buffer
      partOffsets.foreach(o => out.write(cache.remove(o).get))
      out.readByteString()
    };
    ev match {
      case e: ExtendedRawEvent => e.copy(data = data, headers = headers)
      case e => SimpleRawEvent(e.srcId, data, headers)
    }
  }
}
