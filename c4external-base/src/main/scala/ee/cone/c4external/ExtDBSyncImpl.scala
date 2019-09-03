package ee.cone.c4external

import java.net.CacheResponse

import com.squareup.wire.ProtoAdapter
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4actor._
import ee.cone.c4external.ExternalProtocol.S_ExternalUpdate
import ee.cone.c4proto.HasId
import ee.cone.dbadapter.{DBAdapter, DBSchemaBuilder, DBSchemaBuildersApp, TableSchema}

trait ExtDBSyncApp extends DBSchemaBuildersApp with ExtModelsApp {
  def qAdapterRegistry: QAdapterRegistry
  def toUpdate: ToUpdate
  def dbAdapter: DBAdapter

  lazy val extDBSync: ExtDBSync = new ExtDBSyncImpl(dbAdapter, builders, qAdapterRegistry, toUpdate, extModels)
}

class ExtDBSyncImpl(
  dbAdapter: DBAdapter,
  builders: List[DBSchemaBuilder[_ <: Product]],
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  external: List[ExternalModel[_ <: Product]],
  archiveFlag: Long = 2L
) extends ExtDBSync with LazyLogging {
  val patch: List[TableSchema] = dbAdapter.patchSchema(builders.flatMap(_.getSchemas))
  logger.debug(patch.toString())
  val externalsList: List[String] = external.map(_.clName)
  val externalsSet: Set[String] = externalsList.toSet
  val buildersByName: Map[String, DBSchemaBuilder[_ <: Product]] = builders.map(b ⇒ b.getOrigClName → b).toMap
  // Check if registered externals have builder
  val builderMap: Map[Long, DBSchemaBuilder[_ <: Product]] = externalsList.map(buildersByName).map(b ⇒ b.getOrigId → b).toMap
  val supportedIds: Set[Long] = builderMap.keySet
  // Check if registered externals have adapter
  val adaptersById: Map[Long, ProtoAdapter[Product] with HasId] = qAdapterRegistry.byId.view.filterKeys(supportedIds).toMap

  val extUpdate: ProtoAdapter[S_ExternalUpdate] with HasId =
    qAdapterRegistry.byName(classOf[S_ExternalUpdate].getName)
      .asInstanceOf[ProtoAdapter[S_ExternalUpdate] with HasId]

  def upload: List[S_ExternalUpdate] ⇒ List[(String, Int)] = list ⇒ {
    val toWrite: List[(NextOffset, List[S_ExternalUpdate])] = list.filter(u ⇒ (u.flags & archiveFlag) == 0L).groupBy(_.txId).toList.sortBy(_._1)
    (for {
      (offset, qUpdates) ← toWrite
    } yield {
      val (toDelete, toUpdate) = qUpdates.partition(_.value.size() == 0)
      val deletes = toDelete.flatMap(ext ⇒ builderMap(ext.valueTypeId).getDeleteValue(ext.valueSrcId))
      val updates = toUpdate.flatMap(ext ⇒ {
        val builder = builderMap(ext.valueTypeId)
        builder.getUpdateValue(adaptersById(ext.valueTypeId).decode(ext.value))
      }
      )
      logger.debug(s"Writing $offset ${deletes.length}/${updates.length} origs")
      dbAdapter.putOrigs(deletes ::: updates, offset)
    }).flatten.map(t ⇒ t._1.className → t._2)
  }
}
