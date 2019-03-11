
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{Offset, TxRef, Update, Updates}
import ee.cone.c4proto.{HasId, Protocol, ToByteString}

import scala.collection.immutable.{Queue, Seq}
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset
import okio.ByteString

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord

class QMessagesImpl(toUpdate: ToUpdate, getRawQSender: ()⇒RawQSender) extends QMessages {
  //import qAdapterRegistry._
  // .map(o⇒ nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: Context): Context = {
    val updates: List[Update] = WriteModelKey.of(local).toList
    if(updates.isEmpty) return local
    //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
    val (bytes, headers) = toUpdate.toBytes(updates)
    val rec = new QRecordImpl(InboxTopicName(), bytes, headers)
    val debugStr = WriteModelDebugKey.of(local).map(_.toString).mkString("\n---\n")
    val debugRec = new QRecordImpl(LogTopicName(),debugStr.getBytes(UTF_8), Nil)
    val List(offset,_)= getRawQSender().send(List(rec,debugRec))
    Function.chain(Seq(
      WriteModelKey.set(Queue.empty),
      WriteModelDebugKey.set(Queue.empty),
      ReadAfterWriteOffsetKey.set(offset)
    ))(local)
  }
}

class ToUpdateImpl(
  qAdapterRegistry: QAdapterRegistry,
  deCompressorRegistry: DeCompressorRegistry,
  compressorOpt: Option[RawCompressor],
  compressionMinSize: Long
)(
  updatesAdapter: ProtoAdapter[Updates] with HasId =
  qAdapterRegistry.byName(classOf[QProtocol.Updates].getName)
    .asInstanceOf[ProtoAdapter[Updates] with HasId],
  refAdapter: ProtoAdapter[TxRef] with HasId =
  qAdapterRegistry.byName(classOf[TxRef].getName)
    .asInstanceOf[ProtoAdapter[TxRef] with HasId],
  offsetAdapter: ProtoAdapter[Offset] with HasId =
  qAdapterRegistry.byName(classOf[QProtocol.Offset].getName)
    .asInstanceOf[ProtoAdapter[Offset] with HasId],
  fillTxIdFlag: Long = 1L,
  txIdPropId: Long = 0x001A
) extends ToUpdate with LazyLogging {
  def toUpdate[M <: Product](message: LEvent[M]): Update = {
    val valueAdapter = qAdapterRegistry.byName(message.className)
    val byteString = ToByteString(message.value.map(valueAdapter.encode).getOrElse(Array.emptyByteArray))
    val flags = if(message.value.nonEmpty && valueAdapter.props.exists(_.id==txIdPropId)) fillTxIdFlag else 0L
    Update(message.srcId, valueAdapter.id, byteString, flags)
  }

  private val compressionKey = "c"

  private def findCompressor: List[RawHeader] ⇒ Option[DeCompressor] = list ⇒
    list.collectFirst { case header if header.key == compressionKey ⇒ header.value } match {
      case Some(name) ⇒ Option(deCompressorRegistry.byName(name))
      case None ⇒ None
    }

  private def makeHeaderFromName: RawCompressor ⇒ List[RawHeader] = jc ⇒
    RawHeader(compressionKey, jc.name) :: Nil

  def toBytes(updates: List[Update]): (Array[Byte], List[RawHeader]) = {
    val filteredUpdates = updates.filterNot(_.valueTypeId==offsetAdapter.id)
    logger.trace(updates.toString)
    val updatesBytes = updatesAdapter.encode(Updates("", filteredUpdates))
    logger.debug("Compressing...")
    val result = compressorOpt.filter(_ ⇒ updatesBytes.size >= compressionMinSize)
      .fold((updatesBytes, List.empty[RawHeader]))(compressor⇒
        (compressor.compress(updatesBytes), makeHeaderFromName(compressor))
      )
    logger.debug("Finished compressing...")
    result
  }

  def toUpdates(events: List[RawEvent]): List[Update] =
    for {
      event ← events
      update ← {
        val compressorOpt = findCompressor(event.headers)
        logger.trace("Decompressing...")
        val data = compressorOpt.map(_.deCompress(event.data)).getOrElse(event.data)
        logger.trace("Decoding...")
        updatesAdapter.decode(data).updates
      }
    } yield
      if ((update.flags & fillTxIdFlag) == 0) update
      else {
        val ref = TxRef("",event.srcId)
        val value = ToByteString(update.value.toByteArray ++ refAdapter.encode(ref))
        val flags = update.flags & ~fillTxIdFlag
        update.copy(value = value, flags = flags)
      }

  def toKey(up: Update): Update = up.copy(value=ByteString.EMPTY)
  def by(up: Update): (Long, String) = (up.valueTypeId,up.srcId)
}

object QAdapterRegistryFactory {
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters).asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = CheckedMap(adapters.map(a ⇒ a.className → a))
    val byId = CheckedMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new QAdapterRegistry(byName, byId)
  }
}

class LocalQAdapterRegistryInit(qAdapterRegistry: QAdapterRegistry) extends ToInject {
  def toInject: List[Injectable] = QAdapterRegistryKey.set(qAdapterRegistry)
}

/*object NoRawQSender extends RawQSender {
  def send(recs: List[QRecord]): List[NextOffset] = Nil
}*/