package ee.cone.c4external

import java.util.UUID

import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4actor.Types.{SrcId, TypeId}
import ee.cone.c4actor._
import ee.cone.c4assemble.{AssembledKey, IndexUtil}
import ee.cone.c4external.ExternalOrigKey.ExtSrcId
import ee.cone.c4external.ExternalProtocol.S_ExternalUpdate

import scala.collection.immutable.Seq

object RandomUUID {
  def apply(): String = UUID.randomUUID().toString
}

object ExternalOrigKey {
  type ExtSrcId = SrcId
}

class ExtUpdatesPreprocessor(
  toUpdate: ToUpdate,
  qAdapterRegistry: QAdapterRegistry,
  external: List[ExternalModel[_ <: Product]]
)(
  extUpdateId: Long = 4L
) extends UpdateProcessor {
  private val externalNames = external.map(_.clName).toSet
  val idSet: Set[Long] = qAdapterRegistry.byName.collect{ case (k, v) if externalNames(k) => v.id }.toSet

  def process(updates: Seq[N_Update]): Seq[N_Update] = {
    val (ext, normal) = updates.partition(u => idSet(u.valueTypeId) && (u.flags & extUpdateId) == 0L)
    val prepared: Map[TypeId, Map[SrcId, Seq[N_Update]]] = ext.groupBy(_.valueTypeId).view.mapValues(_.groupBy(_.srcId)).toMap
    val extUpdates =
      for {
        pair <- prepared
        (typeId, inner) = pair: (TypeId, Map[SrcId, Seq[N_Update]])
        (_, updates) <- inner
      } yield {
        val randomUid = RandomUUID()
        val u = updates.last
        S_ExternalUpdate(randomUid, u.srcId, typeId, u.value, u.flags, "")
      }
    extUpdates.toSeq.flatMap(LEvent.update).map(toUpdate.toUpdate) ++
      normal.map(u =>
        if ((u.flags & extUpdateId) == 0L)
          u
        else
          u.copy(flags = u.flags & ~extUpdateId)
      )
  }
}

class ExtKeyFactory(composes: IndexUtil, external: List[ExternalModel[_ <: Product]]) extends KeyFactory {
  val externalClassesSet: Set[String] = external.map(_.clName).toSet
  val extKeyAlias: String = "ExtSrcId"
  val extKeyClass: String = classOf[ExtSrcId].getName
  val normalKeyAlias: String = "SrcId"
  val normalKeyClass: String = classOf[SrcId].getName

  def normalKey(className: String): AssembledKey =
    composes.joinKey(was = false, normalKeyAlias, normalKeyClass, className)

  def extKey(className: String): AssembledKey =
    composes.joinKey(was = false, extKeyAlias, extKeyClass, className)

  def rawKey(className: String): AssembledKey =
    if (externalClassesSet(className))
      extKey(className)
    else
      normalKey(className)
}
