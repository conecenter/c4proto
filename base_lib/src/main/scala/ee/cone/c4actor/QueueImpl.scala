
package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.{N_CompressedUpdates, N_TxRef, N_Update, N_UpdateFrom, S_Offset, S_Updates}
import ee.cone.c4proto._

import scala.collection.immutable.{Queue, Seq}
import java.nio.charset.StandardCharsets.UTF_8
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId, TypeId, UpdateKey, UpdateMap}
import ee.cone.c4assemble.Single
import ee.cone.c4di._
import okio.{Buffer, ByteString, Okio, SegmentPool}

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

class QRecordImpl(val topic: TxLogName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord

@c4("ServerCompApp") final class QMessagesImpl(
  toUpdate: ToUpdate, getRawQSender: DeferredSeq[RawQSender],
  flagsCheck: UpdateFlagsCheck, currentTxLogName: CurrentTxLogName,
) extends QMessages with LazyLogging {
  assert(flagsCheck.flagsOk, s"Some of the flags are incorrect: ${flagsCheck.updateFlags}")
  //import qAdapterRegistry._
  // .map(o=> nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: Context): Context = {
    val updates: List[N_UpdateFrom] = WriteModelKey.of(local).toList
    if(updates.isEmpty) local else {
      logger.debug(s"sending: ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
      val (bytes, headers) = toUpdate.toBytes(updates)
      val rec = new QRecordImpl(currentTxLogName, bytes, headers)
      val offset = Single(getRawQSender.value).send(rec)
      logger.debug(s"${updates.size} updates was sent -- $offset")
      (new AssemblerProfiling).debugOffsets("sent", Seq(offset))
      Function.chain(
        Seq(
          WriteModelKey.set(Queue.empty),
          ReadAfterWriteOffsetKey.set(offset)
        )
      )(local)
    }
  }
}

@c4("RichDataCompApp") final class DefUpdateCompressionMinSize extends UpdateCompressionMinSize(1000000L)

@c4("ProtoApp") final class FillTxIdUpdateFlag extends UpdateFlag {
  val flagValue: Long = 1L
}

@c4("ProtoApp") final class ArchiveUpdateFlag extends UpdateFlag {
  val flagValue: Long = 2L
}

@c4("ProtoApp") final class ToUpdateImpl(
  qAdapterRegistry: QAdapterRegistry,
  compressionMinSize: UpdateCompressionMinSize,
  fillTxIdUpdateFlag: FillTxIdUpdateFlag,
  archiveUpdateFlag: ArchiveUpdateFlag,
  execution: Execution,
  compressedUpdatesAdapter: ProtoAdapter[N_CompressedUpdates],
  deCompressors: List[DeCompressor],
  rawCompressor: RawCompressor,
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

  def getInnerSize(up: N_UpdateFrom): Long = up.fromValue.size + up.value.size
  @tailrec private def nextPartSize(updates: List[N_UpdateFrom], count: Long, size: Long): Option[Long] =
    if(updates.isEmpty) None
    else if(size >= compressionMinSize.value) Option(count)
    else nextPartSize(updates.tail,count+1,size+getInnerSize(updates.head))
  @tailrec private def split(in: List[N_UpdateFrom], out: List[List[N_UpdateFrom]]): List[List[N_UpdateFrom]] = {
    nextPartSize(in,0,0) match {
      case None => (in::out).reverse
      case Some(partSize) =>
        val(left,right) = in.splitAt(Math.toIntExact(partSize))
        split(right,left::out)
    }
  }
  private def encode(updates: List[N_UpdateFrom]): Array[Byte] = {
    logger.debug(s"Encoding ${updates.size} updates...")
    val res = updatesAdapter.encode(S_Updates("", updates))
    logger.debug(s"Encoded to ${res.length} bytes")
    res
  }
  def toBytes(updates: List[N_UpdateFrom]): (Array[Byte], List[RawHeader]) = {
    val filteredUpdates = updates.filterNot(_.valueTypeId==offsetAdapter.id)
    if(nextPartSize(filteredUpdates,0,0).isEmpty) (encode(filteredUpdates), List.empty[RawHeader]) else {
      logger.debug(s"Compressing/encoding parts...")
      val res = execution.aWait{ implicit ec =>
        Future.sequence(split(filteredUpdates, Nil).map(u=>Future(new ByteString(rawCompressor.compress(encode(u))))))
          .map(values=>compressedUpdatesAdapter.encode(N_CompressedUpdates(rawCompressor.name, values)))
      }
      logger.debug(s"Compressed")
      (res, RawHeader(compressionKey, "inner") :: Nil)
    }
  }

  private def deCompressDecode(event: RawEvent): List[N_UpdateFrom] = {
    logger.debug("Decompressing/decoding...")
    val res = event.headers.collectFirst { case header if header.key == compressionKey => header.value } match {
      case Some("inner") =>
        execution.aWait { implicit ec =>
          val compressedUpdates = compressedUpdatesAdapter.decode(event.data)
          val compressor = deCompressors.find(_.name==compressedUpdates.compressorName).get
          Future.sequence(compressedUpdates.values.map(v => Future {
            FinallyClose(compressor.deCompress((new Buffer).write(v)))(updatesAdapter.decode(_).updates)
          }))
        }.flatten
      case None => updatesAdapter.decode(event.data).updates
    }
    logger.debug("Decompressing finished...")
    res
  }

  def toUpdates(events: List[RawEvent], hint: String): List[N_UpdateFrom] =
    for {
      event <- events
      up <- toUpdates(event, hint)
    } yield up

  def msNow(): Long = System.nanoTime / 1000000
  def toUpdates(event: RawEvent, hint: String): List[N_UpdateFrom] = {
    val started = msNow()
    val updates = for {
      update <- deCompressDecode(event)
    } yield
      if (update.flags == 0L) update
      else if ((update.flags & fillTxIdFlag) == 0) update.copy(flags = 0L)
      else {
        val ref = N_TxRef("", event.srcId)
        val value = ToByteString(update.value.toByteArray ++ refAdapter.encode(ref))
        logger.debug(s"TxRef filled ${update.srcId} ${event.srcId}")
        update.copy(value = value, flags = 0L)
      }
    val period = msNow() - started
    if(period > 5000) logger.info(s"E2U $hint was for $period ms ")
    logger.debug(
      s"E2U $hint ${event.srcId} " + updates.groupMapReduce(u=>(
        u.valueTypeId,
        "D"*(if(u.fromValue.size > 0) 1 else 0) + "A"*(if(u.value.size > 0) 1 else 0) // D A DA are valid
      ))(_=>1)((a,b)=>a+b).toSeq.sorted.map{
        case ((id,dma),count) =>
          val idStr = java.lang.Long.toHexString(id)
          val idL = s"0x${"0"*Math.max(0, 4-idStr.length)}$idStr"
          val name = qAdapterRegistry.byId.get(id).fold("")(_.protoOrigMeta.cl.getSimpleName)
          s"\n\tE2U $hint $idL:$dma:$count\t$name"
      }.mkString
    )
    updates
  }

  def toUpdatesWithFlags(events: List[RawEvent]): List[N_Update] =
    for {
      event <- events
      update <- deCompressDecode(event)
    } yield toUpdateLost(
      if ((update.flags & fillTxIdFlag) == 0) update
      else {
        val ref = N_TxRef("",event.srcId)
        val value = ToByteString(update.value.toByteArray ++ refAdapter.encode(ref))
        val flags = update.flags & ~fillTxIdFlag
        update.copy(value = value, flags = flags)
      }
    )

  def toUpdateLost(up: N_UpdateFrom): N_Update =
    N_Update(up.srcId,up.valueTypeId,up.value,up.flags)
}

case class CurrentTxLogNameImpl(value: String) extends CurrentTxLogName
@c4("EnvConfigCompApp") final class CurrentTxLogNameProvider(config: Config){
  @provide def get: Seq[CurrentTxLogName] =
    Seq(CurrentTxLogNameImpl(config.get("C4INBOX_TOPIC_PREFIX")))
}
