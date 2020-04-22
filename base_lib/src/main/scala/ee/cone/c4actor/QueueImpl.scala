
package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.{N_CompressedUpdates, N_TxRef, N_Update, S_Offset, S_Updates}
import ee.cone.c4proto._

import scala.collection.immutable.{Queue, Seq}
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId, TypeId}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import okio.ByteString

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

@c4("ProtoApp") final class UpdateFlagsCheck(
  val updateFlags: List[UpdateFlag]
)(
  val flagsOk: Boolean = {
    val flags = updateFlags.map(_.flagValue)
    if (flags.exists(java.lang.Long.bitCount(_) != 1)) false
    else if (flags.distinct.size != flags.size) false
    else true
  }
)

class QRecordImpl(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord

@c4("ServerCompApp") final class QMessagesImpl(toUpdate: ToUpdate, getRawQSender: DeferredSeq[RawQSender], flagsCheck: UpdateFlagsCheck) extends QMessages with LazyLogging {
  assert(flagsCheck.flagsOk, s"Some of the flags are incorrect: ${flagsCheck.updateFlags}")
  //import qAdapterRegistry._
  // .map(o=> nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: Context): Context = {
    val updates: List[N_Update] = WriteModelKey.of(local).toList
    if(updates.isEmpty) local else {
      //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
      val (bytes, headers) = toUpdate.toBytes(updates)
      val rec = new QRecordImpl(InboxTopicName(), bytes, headers)
      val offset = Single(Single(getRawQSender.value).send(List(rec)))
      logger.debug(s"${updates.size} updates was sent -- $offset")
      Function.chain(
        Seq(
          WriteModelKey.set(Queue.empty),
          ReadAfterWriteOffsetKey.set(offset)
        )
      )(local)
    }
  }
}

@c4("RichDataCompApp") final class DefUpdateCompressionMinSize extends UpdateCompressionMinSize(50000000L)

@c4("ProtoApp") final class FillTxIdUpdateFlag extends UpdateFlag {
  val flagValue: Long = 1L
}

@c4("ProtoApp") final class ArchiveUpdateFlag extends UpdateFlag {
  val flagValue: Long = 2L
}

@c4("ProtoApp") final class ToUpdateImpl(
  qAdapterRegistry: QAdapterRegistry,
  deCompressorRegistry: DeCompressorRegistry,
  compressorOpt: Option[MultiRawCompressor],
  compressionMinSize: UpdateCompressionMinSize,
  fillTxIdUpdateFlag: FillTxIdUpdateFlag,
  archiveUpdateFlag: ArchiveUpdateFlag,
  execution: Execution,
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
  fillTxIdFlag: Long = fillTxIdUpdateFlag.flagValue,
  txIdPropId: Long = 0x001A,
  archiveFlag: Long = archiveUpdateFlag.flagValue
)(
  withFillTxId: Set[Long] = qAdapterRegistry.byId.collect{case (k, v) if v.props.exists(_.id == txIdPropId) => k}.toSet
) extends ToUpdate with LazyLogging {

  def toUpdate[M <: Product](message: LEvent[M]): N_Update = {
    val valueAdapter = qAdapterRegistry.byName.getOrElse(message.className,throw new Exception(s"missing ${message.className} adapter"))
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

  private def findCompressor: List[RawHeader] => Option[MultiDeCompressor] = list =>
    list.collectFirst { case header if header.key == compressionKey => header.value } match {
      case Some(name) => Option(deCompressorRegistry.byName(name))
      case None => None
    }

  private def makeHeaderFromName: MultiRawCompressor => List[RawHeader] = jc =>
    RawHeader(compressionKey, jc.name) :: Nil

  implicit val executionContext = execution.mainExecutionContext

  @tailrec private def nextPartSize(updates: List[N_Update], count: Long, size: Long): Option[Long] =
    if(updates.isEmpty) None
    else if(size >= compressionMinSize.value) Option(count)
    else nextPartSize(updates.tail,count+1,size+updates.head.value.size)
  @tailrec private def split(in: List[N_Update], out: List[List[N_Update]]): List[List[N_Update]] = {
    nextPartSize(in,0,0) match {
      case None => (in::out).reverse
      case Some(partSize) =>
        val(left,right) = in.splitAt(Math.toIntExact(partSize))
        split(right,left::out)
    }
  }
  private def encode(updates: List[N_Update]): Array[Byte] = {
    logger.debug(s"Encoding ${updates.size} updates...")
    val res = updatesAdapter.encode(S_Updates("", updates))
    logger.debug(s"Encoded to ${res.length} bytes")
    res
  }
  def toBytes(updates: List[N_Update]): (Array[Byte], List[RawHeader]) = concurrent.blocking{
    val filteredUpdates = updates.filterNot(_.valueTypeId==offsetAdapter.id)
    compressorOpt.filter(_=>nextPartSize(filteredUpdates,0,0).nonEmpty)
      .fold{
        (encode(filteredUpdates), List.empty[RawHeader])
      } { compressor =>
        val parts = split(filteredUpdates, Nil).map(u=>Future(encode(u)))
        logger.debug(s"Compressing ${parts.size} parts...")
        val resF = compressor.compress(parts: _*)
        val res = Await.result(resF, Duration.Inf)
        logger.debug(s"Compressed")
        (res, makeHeaderFromName(compressor))
      }
  }

  private def deCompressDecode(event: RawEvent): List[N_Update] = concurrent.blocking{
    val compressorOpt = findCompressor(event.headers)
    logger.debug("Decompressing...")
    val resF = Future.sequence(
      compressorOpt.fold(List(Future.successful(event.data)))(_.deCompress(event.data))
      .map(f=>f.map{data=>
        logger.debug(s"Decoding ${data.size} bytes...")
        updatesAdapter.decode(data).updates
      })
    ).map(_.flatten)
    val res = Await.result(resF, Duration.Inf)
    logger.debug("Decompressing finished...")
    res
  }

  def toUpdates(events: List[RawEvent]): List[N_Update] =
    for {
      event <- events
      update <- deCompressDecode(event)
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
      update <- deCompressDecode(event)
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
