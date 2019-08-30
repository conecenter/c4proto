package ee.cone.c4external.joiners

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
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
    bind: Each[S_Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(ExternalLoaderTx(bind.srcId + "ExternalLoaderTx", extDBSync, dbAdapter, toUpdate)))

  def CreateUpdateOffsetTx(
    srcId: SrcId,
    bind: Each[S_Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(UpdateOffsetTx(bind.srcId + "UpdateOffsetTx", dbAdapter)))

  /* def CreateFlushTx(
     srcId: SrcId,
     bind: Each[S_Firstborn]
   ): Values[(SrcId, TxTransform)] =
     List(WithPK(FlushTx(bind.srcId + "FlushTx", dbAdapter)))*/
}

case class ExternalLoaderTx(srcId: SrcId, extDBSync: ExtDBSync, dBAdapter: DBAdapter, toUpdate: ToUpdate) extends TxTransform with LazyLogging {
  val externalId: String = dBAdapter.externalId.uName

  def transform(local: Context): Context = {
    phaseZero(local)
  }

  /**
    * 0: Sends new ExternalUpdates to External, converts ExternalUpdates/S_CacheUpdate to Origs
    *
    * @return local with new db offset
    */
  def phaseZero: Context ⇒ Context = l ⇒ {
    val extUpdates = ByPK(classOf[S_ExternalUpdate]).of(l).values.toList
    val cacheResponses = ByPK(classOf[S_CacheUpdate]).of(l).values.toList
    val writeToKafka = ByPK(classOf[WriteToKafka]).of(l).values.toList
    if (extUpdates.nonEmpty) {
      logger.debug(s"Phase Zero: syncing ${extUpdates.size} records to $externalId")
      extDBSync.upload(extUpdates)
    }
    if (extUpdates.nonEmpty || cacheResponses.nonEmpty) {
      val updates = (extUpdates ::: cacheResponses).flatMap(LEvent.delete).map(toUpdate.toUpdate)
      if (writeToKafka.nonEmpty)
        WriteModelAddKey.of(l)(updates ++ writeToKafka.map(_.toKafka))(l)
      else
        WriteModelAddKey.of(l)(updates)(l)
    } else {
      l
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

case class UpdateOffsetTx(srcId: SrcId, dBAdapter: DBAdapter) extends TxTransform with LazyLogging {
  val externalId: String = dBAdapter.externalId.uName

  def transform(local: Context): Context = {
    val dbOffset = dBAdapter.getOffset
    val current = ByPK(classOf[S_ExternalOffset]).of(local).getOrElse(externalId, S_ExternalOffset(externalId, ""))
    val offLocal: Context = if (current.offset != dbOffset) TxAdd(LEvent.update(S_ExternalOffset(externalId, dbOffset)))(local) else local
    SleepUntilKey.set(Instant.now.plusMillis(15000))(offLocal)
  }
}
