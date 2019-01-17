package ee.cone.dbsync

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.ExternalProtocol.ExternalUpdate
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4proto.HasId

import scala.annotation.tailrec

class ExternalDBSync(
  consuming: Consuming,
  dbAdapter: OrigDBAdapter,
  builders: List[OrigSchemaBuilder[_ <: Product]],
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate
) extends Executable {
  def run(): Unit = {
    val schemas = builders.flatMap(_.getSchemas)
    dbAdapter.patchSchema(schemas)
    val nextOffset = dbAdapter.getOffset
    consuming.process(nextOffset, consumer ⇒ {
      consume(consumer)
    }
    )
  }

  val builderMap: Map[Long, OrigSchemaBuilder[_ <: Product]] = builders.map(b ⇒ b.getOrigId → b).toMap
  val supportedIds: Set[Long] = builderMap.keySet
  val adaptersById: Map[Long, ProtoAdapter[Product] with HasId] = qAdapterRegistry.byId.filterKeys(supportedIds)

  val extUpdate: ProtoAdapter[ExternalUpdate] with HasId = qAdapterRegistry.byName(classOf[ExternalUpdate]).asInstanceOf[ProtoAdapter[ExternalUpdate] with HasId]

  @tailrec
  private def consume(consumer: Consumer): Unit = {
    val events = consumer.poll()
    val updByOffset: List[(SrcId, List[ExternalUpdate])] = events.map(ev ⇒
      ev.srcId →
        toUpdate.toUpdates(ev :: Nil)
          .filter(u ⇒ u.valueTypeId == extUpdate.id || u.value.size() > 0)
          .map(u ⇒ extUpdate.decode(u.value))
          .filter(ext ⇒ supportedIds(ext.valueTypeId))
    )
    for {
      (offset, extUpdates) ← updByOffset
    } yield {
      val (toDelete, toUpdate) = extUpdates.partition(_.value.size() == 0)
      val deletes = toDelete.flatMap(ext ⇒ builderMap(ext.valueTypeId).getDeleteValue(ext.srcId))
      val updates = toUpdate.flatMap(ext ⇒ {
        val builder = builderMap(ext.valueTypeId)
        builder.getUpdateValue(adaptersById(ext.valueTypeId).decode(ext.value))})
      dbAdapter.putOrigs(deletes ::: updates, offset)
    }
    consume(consumer)
  }
}
