
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{N_TxRef, N_Update, S_Offset, S_Updates}
import ee.cone.c4proto.{HasId, Protocol, ToByteString, c4component}

import scala.collection.immutable.{Queue, Seq}
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId, TypeId}
import ee.cone.c4assemble.Single
import okio.ByteString

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord

@c4component("ServerCompApp") class QMessagesImpl(toUpdate: ToUpdate, getRawQSender: DeferredSeq[RawQSender]) extends QMessages {
  //import qAdapterRegistry._
  // .map(o=> nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: Context): Context = {
    val updates: List[N_Update] = WriteModelKey.of(local).toList
    if(updates.isEmpty) return local
    //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
    val (bytes, headers) = toUpdate.toBytes(updates)
    val rec = new QRecordImpl(InboxTopicName(), bytes, headers)
    val debugStr = WriteModelDebugKey.of(local).map(_.toString).mkString("\n---\n")
    val debugRec = new QRecordImpl(LogTopicName(),debugStr.getBytes(UTF_8), Nil)
    val List(offset,_)= Single(getRawQSender.value).send(List(rec,debugRec))
    Function.chain(Seq(
      WriteModelKey.set(Queue.empty),
      WriteModelDebugKey.set(Queue.empty),
      ReadAfterWriteOffsetKey.set(offset)
    ))(local)
  }
}

@c4component("RichDataCompApp") class DefUpdateCompressionMinSize extends UpdateCompressionMinSize(50000000L)

@c4component("ProtoApp") class ToUpdateImpl(
  qAdapterRegistry: QAdapterRegistry,
  deCompressorRegistry: DeCompressorRegistry,
  compressorOpt: Option[RawCompressor],
  compressionMinSize: UpdateCompressionMinSize
)(
  updatesAdapter: ProtoAdapter[S_Updates] with HasId =
  qAdapterRegistry.byName(classOf[QProtocol.S_Updates].getName)
    .asInstanceOf[ProtoAdapter[S_Updates] with HasId],
  refAdapter: ProtoAdapter[N_TxRef] with HasId =
  qAdapterRegistry.byName(classOf[N_TxRef].getName)
    .asInstanceOf[ProtoAdapter[N_TxRef] with HasId],
  offsetAdapter: ProtoAdapter[S_Offset] with HasId =
  qAdapterRegistry.byName(classOf[QProtocol.S_Offset].getName)
    .asInstanceOf[ProtoAdapter[S_Offset] with HasId],
  fillTxIdFlag: Long = 1L,
  txIdPropId: Long = 0x001A,
  archiveFlag: Long = 2L
)(
  withFillTxId: Set[Long] = qAdapterRegistry.byId.collect{case (k, v) if v.props.exists(_.id == txIdPropId) => k}.toSet
) extends ToUpdate with LazyLogging {

  def toUpdate[M <: Product](message: LEvent[M]): N_Update = {
    val valueAdapter = qAdapterRegistry.byName(message.className)
    message match {
      case upd: UpdateLEvent[_] =>
        val byteString = ToByteString(valueAdapter.encode(upd.value))
        val txRefFlags = if (withFillTxId(valueAdapter.id)) fillTxIdFlag else 0L
        N_Update(message.srcId, valueAdapter.id, byteString, txRefFlags)
      case _: DeleteLEvent[_] =>
        N_Update(message.srcId, valueAdapter.id, ByteString.EMPTY, 0L)
      case _: ArchiveLEvent[_] =>
        N_Update(message.srcId, valueAdapter.id, ByteString.EMPTY, archiveFlag)
    }
  }

  private val compressionKey = "c"

  private def findCompressor: List[RawHeader] => Option[DeCompressor] = list =>
    list.collectFirst { case header if header.key == compressionKey => header.value } match {
      case Some(name) => Option(deCompressorRegistry.byName(name))
      case None => None
    }

  private def makeHeaderFromName: RawCompressor => List[RawHeader] = jc =>
    RawHeader(compressionKey, jc.name) :: Nil

  def toBytes(updates: List[N_Update]): (Array[Byte], List[RawHeader]) = {
    val filteredUpdates = updates.filterNot(_.valueTypeId==offsetAdapter.id)
    val updatesBytes = updatesAdapter.encode(S_Updates("", filteredUpdates))
    logger.debug("Compressing...")
    val result = compressorOpt.filter(_ => updatesBytes.size >= compressionMinSize.value)
      .fold((updatesBytes, List.empty[RawHeader]))(compressor=>
        (compressor.compress(updatesBytes), makeHeaderFromName(compressor))
      )
    logger.debug("Finished compressing...")
    result
  }

  def toUpdates(events: List[RawEvent]): List[N_Update] =
    for {
      event <- events
      update <- {
        val compressorOpt = findCompressor(event.headers)
        logger.trace("Decompressing...")
        val data = compressorOpt.map(_.deCompress(event.data)).getOrElse(event.data)
        logger.trace("Decoding...")
        updatesAdapter.decode(data).updates
      }
    } yield
      if (update.flags == 0L) update
      else if ((update.flags & fillTxIdFlag) == 0) update.copy(flags = 0L)
      else {
        val ref = N_TxRef("", event.srcId)
        val value = ToByteString(update.value.toByteArray ++ refAdapter.encode(ref))
        update.copy(value = value, flags = 0L)
      }

  def toUpdatesWithFlags(events: List[RawEvent]): List[N_Update] =
    for {
      event <- events
      update <- {
        val compressorOpt = findCompressor(event.headers)
        logger.trace("Decompressing...")
        val data = compressorOpt.map(_.deCompress(event.data)).getOrElse(event.data)
        logger.trace("Decoding...")
        updatesAdapter.decode(data).updates
      }
    } yield
      if ((update.flags & fillTxIdFlag) == 0) update
      else {
        val ref = N_TxRef("",event.srcId)
        val value = ToByteString(update.value.toByteArray ++ refAdapter.encode(ref))
        val flags = update.flags & ~fillTxIdFlag
        update.copy(value = value, flags = flags)
      }


  def toKey(up: N_Update): N_Update = up.copy(value=ByteString.EMPTY)
  def by(up: N_Update): (TypeId, SrcId) = (up.valueTypeId,up.srcId)
}


/*object NoRawQSender extends RawQSender {
  def send(recs: List[QRecord]): List[NextOffset] = Nil
}*/