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

  def transform(local: Context): Context = {
    phaseZero(local)
  }

  /**
    * 0: Sends new ExternalUpdates to External, converts ExternalUpdates/CacheUpdate to Origs
    *
    * @return local with new db offset
    */
  def phaseZero: Context ⇒ Context = l ⇒ {
    val dbOffset = dBAdapter.getOffset
    val current = ByPK(classOf[ExternalOffset]).of(l).getOrElse(externalId, ExternalOffset(externalId, ""))
    val offLocal: Context = if (current.offset != dbOffset) TxAdd(LEvent.update(ExternalOffset(externalId, dbOffset)))(l) else l
    val extUpdates = ByPK(classOf[ExternalUpdate]).of(offLocal).values.toList
    val cacheResponses = ByPK(classOf[CacheUpdate]).of(offLocal).values.toList
    val writeToKafka = ByPK(classOf[WriteToKafka]).of(offLocal).values.toList
    if (extUpdates.nonEmpty)
      logger.debug(s"Phase Zero: syncing ${extUpdates.size} records to $externalId")
    extDBSync.upload(extUpdates)
    (TxAdd(cacheResponses.flatMap(LEvent.delete)) andThen WriteModelAddKey.of(l)(writeToKafka.map(_.toKafka)))(offLocal)
  }
}

case class FlushTx(srcId: SrcId, dBAdapter: DBAdapter) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    dBAdapter.flush
    logger.debug("Flushed DB")
    local
  }
}
