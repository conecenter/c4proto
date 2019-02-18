package ee.cone.c4external

import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{TxRef, Update}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4external.ExternalProtocol.{CacheResponses, ExternalUpdates}
import ee.cone.c4proto.{HasId, Protocol}
import okio.ByteString

import scala.collection.immutable.Seq

object RandomUUID {
  def apply(): String = UUID.randomUUID().toString
}

class ExtUpdatesPreprocessorImpl(toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry, external: List[Class[_ <: Product]]) extends ExtUpdateProcessor {
  private val externalNames = external.map(_.getName).toSet
  val idSet: Set[Long] = qAdapterRegistry.byName.filterKeys(externalNames).transform { case (_, v) ⇒ v.id }.values.toSet

  def process(updates: Seq[Update]): Seq[Update] = {
    if (updates.exists(u ⇒ idSet(u.valueTypeId))) {
      val (ext, normal) = updates.partition(u ⇒ idSet(u.valueTypeId))
      val srcId = RandomUUID()
      Seq(ExternalUpdates(srcId, System.currentTimeMillis(), ext.toList), TxRef(srcId, "")).flatMap(LEvent.update).map(toUpdate.toUpdate) ++ normal
    }
    else {
      updates
    }
  }
}

case class ExternalUpdate[Model <: Product](srcId: SrcId, update: Update, offset: NextOffset) {
  def origValue: ByteString = update.value
}

case class CacheResponse[Model <: Product](srcId: SrcId, update: Update, offset: NextOffset) {
  def origValue: ByteString = update.value
}

trait ExternalUpdateUtil[Model <: Product] {
  type TxRefId[ModelType] = SrcId
  def adapter: ProtoAdapter[Model] with HasId
  def decode: ByteString ⇒ Values[(SrcId, Model)] = bs ⇒
    if (bs.size() == 0)
      Nil
    else
      WithPK(adapter.decode(bs)) :: Nil

}

@assemble class ExternalOrigJoiner[Model <: Product](
  modelCl: Class[Model],
  modelId: Long,
  qAdapterRegistry: QAdapterRegistry
)(
  val adapter: ProtoAdapter[Model] with HasId = qAdapterRegistry.byId(modelId).asInstanceOf[ProtoAdapter[Model] with HasId]
) extends Assemble with ExternalUpdateUtil[Model] {
  type MergeId = SrcId
  type CombineId = SrcId

  def ToMergeExtUpdate(
    origId: SrcId,
    extU: Each[ExtUpdatesWithTxId]
  ): Values[(MergeId, ExternalUpdate[Model])] =
    extU.updates
      .filter(_.valueTypeId == modelId)
      .map(u ⇒ u.srcId → ExternalUpdate[Model](extU.txId + u.srcId, u, extU.txId))

  def ToSingleExtUpdate(
    origId: SrcId,
    @by[MergeId] extUs: Values[ExternalUpdate[Model]]
  ): Values[(CombineId, ExternalUpdate[Model])] =
    if (extUs.nonEmpty) {
      val u = extUs.maxBy(_.offset)
      List(u.update.srcId → u)
    } else Nil

  def ToMergeCacheResponse(
    origId: SrcId,
    cResp: Each[CacheResponses]
  ): Values[(MergeId, CacheResponse[Model])] =
    cResp.updates
      .filter(_.valueTypeId == modelId)
      .map(u ⇒ u.srcId → CacheResponse[Model](cResp.externalOffset + u.srcId, u, cResp.externalOffset))

  def ToSingleCacheResponse(
    origId: SrcId,
    @by[MergeId] cResps: Values[CacheResponse[Model]]
  ): Values[(CombineId, CacheResponse[Model])] =
    if (cResps.nonEmpty) {
      val u = cResps.maxBy(_.offset)
      List(u.update.srcId → u)
    } else Nil

  def CreateExternal(
    origId: SrcId,
    @by[CombineId] externals: Values[ExternalUpdate[Model]],
    @by[CombineId] caches: Values[CacheResponse[Model]]
  ): Values[(SrcId, Model)] =
    (Single.option(externals), Single.option(caches)) match {
      case (Some(e), None) ⇒ decode(e.origValue)
      case (None, Some(c)) ⇒ decode(c.origValue)
      case (Some(e), Some(c)) ⇒
        if (e.offset > c.offset)
          decode(e.origValue)
        else if (e.offset < c.offset)
          decode(c.origValue)
        else {
          assert(e.origValue == c.origValue, s"Same offset, different values: $e, $c")
          decode(e.origValue)
        }
      case _ ⇒ Nil
    }

}
