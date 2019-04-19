package ee.cone.c4external.joiners

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocolBase.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.assemble
import ee.cone.c4external.ExtDBSync
import ee.cone.c4external.ExternalProtocol._
import ee.cone.dbadapter.DBAdapter

@assemble class ProduceExternalTransformsBase(dbAdapter: DBAdapter, extDBSync: ExtDBSync, toUpdate: ToUpdate) {
  def CreateExternalLoaderTx(
    srcId: SrcId,
    bind: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(ExternalLoaderTx(bind.srcId + "ExternalLoaderTx", extDBSync, dbAdapter, toUpdate)))

  def CreateFlushTx(
    srcId: SrcId,
    bind: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(FlushTx(bind.srcId + "FlushTx", dbAdapter)))
}

case class ExternalLoaderTx(srcId: SrcId, extDBSync: ExtDBSync, dBAdapter: DBAdapter, toUpdate: ToUpdate) extends TxTransform with LazyLogging {
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
    val updates = cacheResponses.flatMap(LEvent.delete).map(toUpdate.toUpdate)
    WriteModelAddKey.of(offLocal)(updates ++ writeToKafka.map(_.toKafka))(offLocal)
  }
}

case class FlushTx(srcId: SrcId, dBAdapter: DBAdapter) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    dBAdapter.flush
    logger.debug("Flushed DB")
    local
  }
}
