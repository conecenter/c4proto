package ee.cone.c4external

import com.squareup.wire.ProtoAdapter
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4external.ExternalProtocol.ExternalUpdates
import ee.cone.c4proto.HasId
import ee.cone.dbadapter.{DBAdapter, OrigSchema, OrigSchemaBuilder, OrigSchemaBuildersApp}

trait ExtDBSyncApp extends OrigSchemaBuildersApp with ExtModelsApp {
  def qAdapterRegistry: QAdapterRegistry
  def toUpdate: ToUpdate
  def consuming: Consuming
  def dbAdapter: DBAdapter

  val extDBSync: ExtDBSync = new ExtDBSyncImpl(dbAdapter, builders, qAdapterRegistry, toUpdate, external)
}

class ExtDBSyncImpl(
  dbAdapter: DBAdapter,
  builders: List[OrigSchemaBuilder[_ <: Product]],
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  external: List[Class[_ <: Product]]
) extends ExtDBSync with LazyLogging {

  val externals: List[SrcId] = external.map(_.getName)
  val buildersByName: Map[String, OrigSchemaBuilder[_ <: Product]] = builders.map(b ⇒ b.getOrigClName → b).toMap
  // Check if registered externals have builder
  val builderMap: Map[Long, OrigSchemaBuilder[_ <: Product]] = externals.map(buildersByName).map(b ⇒ b.getOrigId → b).toMap
  val supportedIds: Set[Long] = builderMap.keySet
  // Check if registered externals have adapter
  val adaptersById: Map[Long, ProtoAdapter[Product] with HasId] = qAdapterRegistry.byId.filterKeys(supportedIds)

  val extUpdate: ProtoAdapter[ExternalUpdates] with HasId =
    qAdapterRegistry.byName(classOf[ExternalUpdates].getName)
      .asInstanceOf[ProtoAdapter[ExternalUpdates] with HasId]

  def upload: List[ExtUpdatesWithTxId] ⇒ List[(String, Int)] = list ⇒ {
    val toWrite: List[(NextOffset, List[QProtocol.Update])] = list.map(u ⇒
      u.txId → u.updates
    )
    (for {
      (offset, qUpdates) ← toWrite
    } yield {
      val (toDelete, toUpdate) = qUpdates.partition(_.value.size() == 0)
      val deletes = toDelete.flatMap(ext ⇒ builderMap(ext.valueTypeId).getDeleteValue(ext.srcId))
      val updates = toUpdate.flatMap(ext ⇒ {
        val builder = builderMap(ext.valueTypeId)
        builder.getUpdateValue(adaptersById(ext.valueTypeId).decode(ext.value))
      }
      )
      logger.debug(s"Writing $offset $deletes/$updates origs")
      dbAdapter.putOrigs(deletes ::: updates, offset)
    }).flatten.map(t ⇒ t._1.className → t._2)
  }
  
  def download: List[ByPKExtRequest] ⇒ List[QProtocol.Update] = list ⇒ {
    val loadList: List[(OrigSchema, List[SrcId])] = list.groupBy(_.modelId).toList.filter(p ⇒ supportedIds(p._1)).collect { case (k, v) ⇒ builderMap(k).getMainSchema → v.map(_.modelSrcId) }
    loadList.flatMap { case (sch, pks) ⇒ dbAdapter.getOrigBytes(sch, pks) }
  }
}
