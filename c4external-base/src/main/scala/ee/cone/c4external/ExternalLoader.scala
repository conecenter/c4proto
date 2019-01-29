package ee.cone.c4external

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, TxRef, Update}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4external.ExternalProtocol.{ExternalOffset, ExternalReady, ExternalUpdates}
import ee.cone.dbadapter.{DBAdapter, OrigSchema, OrigSchemaBuildersApp}

trait ExternalLoaderMix extends AssemblesApp with OrigSchemaBuildersApp{
  def extDBSync: ExtDBSync
  def dbAdapter: DBAdapter
  override def assembles: List[Assemble] = {
    dbAdapter.patchSchema(builders.flatMap(_.getSchemas))
    new ExternalLoaderAssemble(extDBSync, dbAdapter) :: super.assembles}
}

case class ExtUpdatesWithTxId(srcId: SrcId, txId: NextOffset, updates: List[Update])

case class ExtUpdatesNewerThan(srcId: String, olderThen: NextOffset, externals: List[ExtUpdatesWithTxId])

@assemble class ExternalLoaderAssemble(extDBSync: ExtDBSync, dbAdapter: DBAdapter) extends Assemble {
  type OffsetAll = All
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
      List(offset.externalName → ext)
    else
      Nil

  def ExternalUpdatesOlderCreate(
    externalId: SrcId,
    @by[OffsetId] exts: Values[ExtUpdatesWithTxId],
    @by[OffsetAll] offset: Each[ExternalOffset]
  ): Values[(SrcId, ExtUpdatesNewerThan)] =
    List(WithPK(ExtUpdatesNewerThan(externalId, offset.offset, exts.toList)))

  def CreateExternalLoaderTx(
    srcId: SrcId,
    fb: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(ExternalLoaderTx(fb.srcId + "ExternalLoaderTx", extDBSync, dbAdapter)))

  def CreateFlushTx(
    srcId: SrcId,
    fb: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(FlushTx(fb.srcId + "FlushTx", dbAdapter)))
}

case class ExternalLoaderTx(srcId: SrcId, extDBSync: ExtDBSync, dBAdapter: DBAdapter) extends TxTransform {
  def transform(local: Context): Context = {
    val zeroLocal = phaseZero(local)
    // TODO return here

    zeroLocal
  }

  def phaseZero: Context ⇒ Context = l ⇒ {
    val dbOffset = dBAdapter.getOffset
    val current = ByPK(classOf[ExternalOffset]).of(l).getOrElse(dBAdapter.externalName, ExternalOffset(dBAdapter.externalName, ""))
    val offLocal: Context = if (current.offset != dbOffset) TxAdd(LEvent.update(ExternalOffset(dBAdapter.externalName, dbOffset)))(l) else l
    val grouped = ByPK(classOf[ExtUpdatesNewerThan]).of(offLocal).values.toList
    extDBSync.sync(grouped.flatMap(_.externals))
    offLocal
  }
}

case class FlushTx(srcId: SrcId, dBAdapter: DBAdapter) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    dBAdapter.flush
    logger.debug("Flushed DB")
    local
  }
}
