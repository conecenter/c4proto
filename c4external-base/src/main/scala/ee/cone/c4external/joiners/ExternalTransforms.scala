package ee.cone.c4external.joiners

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.assemble
import ee.cone.c4external.ExternalProtocol._
import ee.cone.c4external.{ExtDBRequestGroup, ExtDBSync}
import ee.cone.dbadapter.DBAdapter

import scala.annotation.tailrec

@assemble class ProduceExternalTransformsBase(dbAdapter: DBAdapter, extDBSync: ExtDBSync, tickMillis: Long) {
  def CreateExternalLoaderTx(
    srcId: SrcId,
    bind: Each[ExternalIdBind]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(ExternalLoaderTx(bind.externalName + "ExternalLoaderTx", extDBSync, dbAdapter, tickMillis)))

  def CreateFlushTx(
    srcId: SrcId,
    bind: Each[ExternalIdBind]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(FlushTx(bind.externalName + "FlushTx", dbAdapter)))
}

case class ExternalLoaderTx(srcId: SrcId, extDBSync: ExtDBSync, dBAdapter: DBAdapter, timeTickMillis: Long) extends TxTransform with LazyLogging {
  val externalId: String = dBAdapter.externalId.uName
  val zeroOffset: NextOffset = "0000000000000000"
  val cacheRespClName: String = classOf[CacheResponses].getName
  val extUpdateClName: String = classOf[ExternalUpdates].getName

  def transform(local: Context): Context = {
    val zeroLocal = phaseZero(local)
    val extTime = ByPK(classOf[ExternalTime]).of(zeroLocal).get(externalId).map(_.time).getOrElse(0L)
    val currentTime = System.currentTimeMillis()
    val updateTimeOpt = if (currentTime > extTime + timeTickMillis - 5L) Option(ExternalTime(externalId, currentTime)) else None
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
    val grouped = ByPK(classOf[ExtUpdatesToSync]).of(offLocal).values.toList.flatMap(_.externals)
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
    val toProcess: List[ExtDBRequestGroup] = ByPK(classOf[RequestsToProcess]).of(l).get(externalId).map(_.rqs).getOrElse(Nil)
    if (toProcess.isEmpty)
      l
    else {
      logger.debug(s"Phase one/$depth: processing ${toProcess.size} groups")
      val responses: List[CacheResponses] = extDBSync.download(toProcess)
      val updatedLocal = TxAdd(responses.flatMap(LEvent.update))(l)
      phaseOneRec(updatedLocal, depth + 1)
    }
  }

  def phaseTwo: Context ⇒ Context = l ⇒ {
    val minOffsetResp: Option[MinOffsetResponse] = ByPK(classOf[MinOffsetResponse]).of(l).get(externalId)
    minOffsetResp match {
      case Some(oldNew) ⇒
        val minOffset: NextOffset = oldNew.minOffsetOpt.getOrElse(zeroOffset)
        val systemMinOffset = ByPK(classOf[ExternalMinValidOffset]).of(l).get(externalId).map(_.minValidOffset).getOrElse(zeroOffset)

        if (minOffset != systemMinOffset) {
          val minOffsetLocal = TxAdd(LEvent.update(ExternalMinValidOffset(externalId, minOffset)))(l)

          val toDeleteOpt = ByPK(classOf[ExternalsToDelete]).of(minOffsetLocal).get(externalId)
          toDeleteOpt match {
            case Some(toDelete) ⇒
              val lEvents = toDelete.cacheResponses.map(_.srcId).map(LEvent(_, cacheRespClName, None)) ++ toDelete.extUpdates.map(_.srcId).map(LEvent(_, extUpdateClName, None))
              logger.debug(s"Phase Two: deleting ${lEvents.size} external parts")
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
