package ee.cone.c4external

import java.util.UUID

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{SrcId, TypeId}
import ee.cone.c4actor._
import ee.cone.c4external.ExternalProtocolBase.ExternalUpdates

import scala.collection.immutable.Seq

object RandomUUID {
  def apply(): String = UUID.randomUUID().toString
}

class ExtUpdatesPreprocessorImpl(toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry, external: List[ExternalModel[_ <: Product]]) extends ExtUpdateProcessor {
  private val externalNames = external.map(_.clName).toSet
  val idSet: Set[Long] = qAdapterRegistry.byName.filterKeys(externalNames).transform { case (_, v) ⇒ v.id }.values.toSet

  def process(updates: Seq[Update]): Seq[Update] = {
    if (updates.exists(u ⇒ idSet(u.valueTypeId))) {
      val nowTime = System.currentTimeMillis()
      val (ext, normal) = updates.partition(u ⇒ idSet(u.valueTypeId))
      val prepared: Map[TypeId, Map[SrcId, Seq[Update]]] = ext.groupBy(_.valueTypeId).mapValues(_.groupBy(_.srcId))
      val extUpdates = for {
        (typeId, inner) ← prepared
      } yield {
        val updates = for {
          (_, updates) ← inner
        } yield {
          updates.last
        }
        ExternalUpdates(RandomUUID(), "", typeId, nowTime, updates.toList)
      }
      extUpdates.to[Seq].flatMap(LEvent.update).map(toUpdate.toUpdate) ++ normal
    }
    else {
      updates
    }
  }
}
