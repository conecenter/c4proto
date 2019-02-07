package ee.cone.c4external

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, TxRef, Update}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4external.ByPKTypes.ByPKRqId
import ee.cone.c4external.ExternalProtocol._
import ee.cone.c4external.ExternalTypes.{DeletionId, OffsetAll, SatisfactionId}
import ee.cone.dbadapter.{DBAdapter, OrigSchemaBuildersApp}

import scala.annotation.tailrec

trait ExternalLoaderMix extends AssemblesApp with OrigSchemaBuildersApp {
  def extDBSync: ExtDBSync
  def dbAdapter: DBAdapter

  def externalSyncTimeOut = 60000L

  override def assembles: List[Assemble] = {
    dbAdapter.patchSchema(builders.flatMap(_.getSchemas))
    new ExternalRequestsHandler ::
      new ExternalLoaderAssemble(extDBSync, dbAdapter, externalSyncTimeOut) :: super.assembles
  }
}

object ExternalTypes {
  type OffsetAll = All
  type DeletionId = SrcId
  type SatisfactionId = SrcId
  val oldKey: String = "Old"
  val newKey: String = "New"
  val byPKLiveTime: Long = 10L * 60L * 1000L
}

case class ExtUpdatesWithTxId(srcId: SrcId, txId: NextOffset, updates: List[Update])

case class ExtUpdatesNewerThan(srcId: String, olderThen: NextOffset, externals: List[ExtUpdatesWithTxId])

case class ExtUpdatesForDeletion(srcId: SrcId)

@assemble class ExternalLoaderAssemble(extDBSync: ExtDBSync, dbAdapter: DBAdapter, timeOut: Long) extends Assemble {

  type OffsetId = SrcId

  def ExternalUpdatesWithTx(
    srcId: SrcId,
    ext: Each[ExternalUpdates],
    txRef: Each[TxRef]
  ): Values[(SrcId, ExtUpdatesWithTxId)] =
    List(WithPK(ExtUpdatesWithTxId(ext.srcId, txRef.txId, ext.updates)))

  def DBOffsetToAll(
    dbOffsetId: SrcId,
    dbOffset: Each[ExternalOffset]
  ): Values[(OffsetAll, ExternalOffset)] =
    List(WithAll(dbOffset))

  def ExternalsOlderThen(
    extId: SrcId,
    ext: Each[ExtUpdatesWithTxId],
    @by[OffsetAll] offset: Each[ExternalOffset]
  ): Values[(OffsetId, ExtUpdatesWithTxId)] =
    if (ext.txId > offset.offset)
      List(offset.externalId → ext)
    else
      Nil

  def ExternalUpdatesNewerThanCreate(
    externalId: SrcId,
    @by[OffsetId] exts: Values[ExtUpdatesWithTxId],
    offset: Each[ExternalOffset]
  ): Values[(SrcId, ExtUpdatesNewerThan)] =
    if (exts.nonEmpty)
      List(WithPK(ExtUpdatesNewerThan(externalId, offset.offset, exts.toList)))
    else
      Nil

  def ExtUpdatesForDeletionCreate(
    extUpdId: SrcId,
    extUpd: Each[ExtUpdatesWithTxId],
    @by[OffsetAll] minOffset: Each[ExternalMinValidOffset]
  ): Values[(DeletionId, ExtUpdatesForDeletion)] =
    if (extUpd.txId < minOffset.minValidOffset)
      List(minOffset.externalId → ExtUpdatesForDeletion(extUpd.srcId))
    else
      Nil

  def CreateExternalLoaderTx(
    srcId: SrcId,
    fb: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(ExternalLoaderTx(fb.srcId + "ExternalLoaderTx", extDBSync, dbAdapter, timeOut)))

  def CreateFlushTx(
    srcId: SrcId,
    fb: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(FlushTx(fb.srcId + "FlushTx", dbAdapter)))
}

case class Satisfaction(srcId: SrcId, respId: SrcId, externalId: SrcId, offset: NextOffset, status: Symbol) // 'new, 'old

case class ByPKExtRequestWStatus(rq: ByPKExtRequest, status: Int) // 0 - unsatisfied, 1 - old resp

case class MassByPKRequest(externalId: SrcId, rqs: List[ByPKExtRequestWStatus])

case class OldNewCacheResponses(externalId: SrcId, expired: List[SrcId], relevantOffsets: List[NextOffset])

case class CacheResponseForDeletion(srcId: SrcId)

case class GarbageContainer(externalId: SrcId, extUpds: List[ExtUpdatesForDeletion], cacheResp: List[CacheResponseForDeletion])


@assemble class ExternalRequestsHandler extends Assemble {
  type ExternalTimeAll = All
  type ByPKCacheUpdate = SrcId
  type ByPkCombinedId = SrcId

  def ExternalTimeToAll(
    externalId: SrcId,
    time: Each[ExternalTime]
  ): Values[(ExternalTimeAll, ExternalTime)] =
    List(WithAll(time))

  def ExternalMinOffsetToAll(
    externalId: SrcId,
    offset: Each[ExternalMinValidOffset]
  ): Values[(OffsetAll, ExternalMinValidOffset)] =
    List(WithAll(offset))

  def CacheResponsesForDeletionCreate(
    cacheRespId: SrcId,
    cacheResp: Each[CacheResponses],
    @by[OffsetAll] minOffset: Each[ExternalMinValidOffset]
  ): Values[(DeletionId, CacheResponseForDeletion)] =
    if (cacheResp.externalOffset < minOffset.minValidOffset)
      List(minOffset.externalId → CacheResponseForDeletion(cacheResp.srcId))
    else
      Nil

  def GarbageContainerCreation(
    externalId: SrcId,
    @by[DeletionId] eufd: Values[ExtUpdatesForDeletion],
    @by[DeletionId] crfd: Values[CacheResponseForDeletion]
  ): Values[(SrcId, GarbageContainer)] =
    List(WithPK(GarbageContainer(externalId, eufd.toList, crfd.toList)))

  def ProduceSatisfaction(
    responseId: SrcId,
    response: Each[CacheResponses],
    @by[OffsetAll] offset: Each[ExternalOffset],
    @by[ExternalTimeAll] time: Each[ExternalTime]
  ): Values[(SatisfactionId, Satisfaction)] = {
    val status: Symbol = if (response.externalOffset == offset.offset || response.validUntil > time.time) 'new else 'old
    val offsetR: String = response.externalOffset
    val srcId: String = status + response.srcId // Trick to optimize satisfactions.exists(_.status == 1) in the next joiner
    for {
      reqId ← time.externalId :: response.reqIds
    } yield reqId → Satisfaction(srcId, response.srcId, time.externalId, offsetR, status)
  }

  def OldNewResponses(
    externalId: SrcId,
    offset: Each[ExternalOffset],
    @by[SatisfactionId] cacheStatus: Values[Satisfaction]
  ): Values[(SrcId, OldNewCacheResponses)] =
    if (cacheStatus.nonEmpty) {
      val (expired, relevant) = cacheStatus.partition(_.status == 'old)
      List(WithPK(OldNewCacheResponses(externalId, expired.map(_.respId).toList, relevant.map(_.offset).toList)))
    } else
      Nil

  def MockByPK(
    fbId: SrcId,
    fb: Each[Firstborn]
  ): Values[(ByPKRqId, ByPKExtRequest)] =
    Nil

  def CombineByPK(
    byPKRq: SrcId,
    @distinct @by[ByPKRqId] byPks: Values[ByPKExtRequest]
  ): Values[(ByPkCombinedId, ByPKExtRequest)] =
    Single.option(byPks).map(rq ⇒ List(WithPK(rq))).getOrElse(Nil)

  def HandleByPKExtRequest(
    rqId: SrcId,
    @by[ByPkCombinedId] rq: Each[ByPKExtRequest],
    @by[SatisfactionId] satisfactions: Values[Satisfaction]
  ): Values[(ByPKCacheUpdate, ByPKExtRequestWStatus)] =
    if (satisfactions.isEmpty)
      List((rq.externalId + ExternalTypes.newKey) → ByPKExtRequestWStatus(rq, 0))
    else if (satisfactions.exists(_.status == 'new))
      Nil
    else
      List((rq.externalId + ExternalTypes.oldKey) → ByPKExtRequestWStatus(rq, 1))

  def HandleMassByPKRequest(
    externalIdWStatus: SrcId,
    @by[ByPKCacheUpdate] rqs: Values[ByPKExtRequestWStatus]
  ): Values[(SrcId, MassByPKRequest)] =
    List(WithPK(MassByPKRequest(externalIdWStatus, rqs.toList)))
}

case class ExternalLoaderTx(srcId: SrcId, extDBSync: ExtDBSync, dBAdapter: DBAdapter, timeOut: Long) extends TxTransform with LazyLogging {
  val externalId: String = dBAdapter.externalName
  val externalOld: String = externalId + ExternalTypes.oldKey
  val externalNew: String = externalId + ExternalTypes.newKey
  val zeroOffset: NextOffset = "0000000000000000"
  val cacheRespClName: String = classOf[CacheResponses].getName
  val extUpdateClName: String = classOf[ExternalUpdates].getName

  def transform(local: Context): Context = {
    val zeroLocal = phaseZero(local)
    val extTime = ByPK(classOf[ExternalTime]).of(zeroLocal).get(externalId).map(_.time).getOrElse(0L)
    val currentTime = System.currentTimeMillis()
    val updateTimeOpt = if (currentTime > extTime + timeOut - 5L) Option(ExternalTime(externalId, currentTime)) else None
    val newTimeLocal = updateTimeOpt.map(t ⇒ TxAdd(LEvent.update(t))(zeroLocal)).getOrElse(zeroLocal)
    val oneLocal = phaseOne(newTimeLocal)
    val twoLocal = if (updateTimeOpt.isDefined) phaseTwo(oneLocal) else oneLocal
    twoLocal
  }

  /**
    * 0: Sends new External updates to External
    *
    * @return local with new db offset
    */
  def phaseZero: Context ⇒ Context = l ⇒ {
    val dbOffset = dBAdapter.getOffset
    val current = ByPK(classOf[ExternalOffset]).of(l).getOrElse(externalId, ExternalOffset(externalId, ""))
    val offLocal: Context = if (current.offset != dbOffset) TxAdd(LEvent.update(ExternalOffset(externalId, dbOffset)))(l) else l
    val grouped = ByPK(classOf[ExtUpdatesNewerThan]).of(offLocal).values.toList.flatMap(_.externals)
    if (grouped.nonEmpty)
      logger.debug(s"Phase Zero: syncing ${grouped.size} records to $externalId")
    extDBSync.upload(grouped)
    offLocal
  }

  /**
    * 1: Satisfies new requests and updates expired ones by timeout
    *
    * @return local with resolved old requests and new responses
    */
  def phaseOne: Context ⇒ Context = l ⇒ {
    phaseOneRec(l)
  }

  @tailrec
  private def phaseOneRec(l: Context, depth: Int = 0): Context = {
    val newRequests: List[ByPKExtRequest] = ByPK(classOf[MassByPKRequest]).of(l).get(externalNew).map(_.rqs).getOrElse(Nil).map(_.rq)
    val expiredRqs: List[ByPKExtRequest] = ByPK(classOf[MassByPKRequest]).of(l).get(externalOld).map(_.rqs).getOrElse(Nil).map(_.rq)
    if (newRequests.isEmpty && expiredRqs.isEmpty)
      l
    else {
      logger.debug(s"Phase one/$depth: loading ${newRequests.size} new/ ${expiredRqs.size} expired")
      val newResults = extDBSync.download(newRequests)
      val expResults = extDBSync.download(expiredRqs)
      val offset = dBAdapter.getOffset
      val rqIds = newRequests.map(_.srcId) ++ expiredRqs.map(_.srcId)
      val cacheResponse = CacheResponses(RandomUUID(), offset, System.currentTimeMillis() + ExternalTypes.byPKLiveTime, rqIds, newResults ++ expResults)
      val updatedLocal = TxAdd(LEvent.update(cacheResponse))(l)
      phaseOneRec(updatedLocal, depth + 1)
    }
  }

  def phaseTwo: Context ⇒ Context = l ⇒ {
    val oldNewOpt = ByPK(classOf[OldNewCacheResponses]).of(l).get(externalId)
    oldNewOpt match {
      case Some(oldNew) ⇒
        val minOffset: NextOffset =
          if (oldNew.relevantOffsets.isEmpty)
            zeroOffset
          else
            oldNew.relevantOffsets.min
        val systemMinOffset = ByPK(classOf[ExternalMinValidOffset]).of(l).get(externalId).map(_.minValidOffset).getOrElse(zeroOffset)

        if (minOffset != systemMinOffset) {
          val minOffsetLocal = TxAdd(LEvent.update(ExternalMinValidOffset(externalId, minOffset)))(l)

          val toDeleteOpt = ByPK(classOf[GarbageContainer]).of(minOffsetLocal).get(externalId)
          toDeleteOpt match {
            case Some(toDelete) ⇒
              val lEvents = toDelete.cacheResp.map(_.srcId).map(LEvent(_, cacheRespClName, None)) ++ toDelete.extUpds.map(_.srcId).map(LEvent(_, extUpdateClName, None))
              logger.debug(s"Phase Two: deleting ${lEvents.size} entities")
              TxAdd(lEvents)(minOffsetLocal)
            case None ⇒
              minOffsetLocal

          }
        } else
          l
      case None ⇒ l
    }
  }


}

case class FlushTx(srcId: SrcId, dBAdapter: DBAdapter) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    dBAdapter.flush
    logger.debug("Flushed DB")
    local
  }
}
