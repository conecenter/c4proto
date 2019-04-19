package ee.cone.c4external.joiners

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, assemble, by}
import ee.cone.c4external.ExternalProtocol.{CacheUpdate, ExternalUpdate}
import ee.cone.c4proto.HasId
import okio.ByteString


trait ExternalUpdateUtil[Model <: Product] {
  type TxRefId[ModelType] = SrcId
  def adapter: ProtoAdapter[Model] with HasId
  def decode: ByteString ⇒ Values[(SrcId, Model)] = bs ⇒
    if (bs.size() == 0)
      Nil
    else
      WithPK(adapter.decode(bs)) :: Nil

}

import ee.cone.c4external.ExternalOrigKey._

case class WriteToKafka(origSrcId: SrcId, toKafka: Update)

object WriteToKafka {
  private val extUpdateId: Long = 4L

  def apply(ext: ExternalUpdate): List[(SrcId, WriteToKafka)] = (ext.valueSrcId -> WriteToKafka(ext.valueSrcId, Update(ext.valueSrcId, ext.valueTypeId, ext.value, ext.flags | extUpdateId))) :: Nil
  def apply(ext: CacheUpdate): List[(SrcId, WriteToKafka)] = (ext.valueSrcId -> WriteToKafka(ext.valueSrcId, Update(ext.valueSrcId, ext.valueTypeId, ext.value, extUpdateId))) :: Nil
}

object MergeTypes {
  type MergeId[T] = SrcId
  type CombineId[T] = SrcId
}

import MergeTypes._

@assemble class ExternalOrigJoinerBase[Model <: Product](
  modelCl: Class[Model],
  modelId: Long,
  qAdapterRegistry: QAdapterRegistry
)(
  val adapter: ProtoAdapter[Model] with HasId = qAdapterRegistry.byId(modelId).asInstanceOf[ProtoAdapter[Model] with HasId]
) extends ExternalUpdateUtil[Model] {

  def ToMergeExtUpdate(
    origId: SrcId,
    extU: Each[ExternalUpdate]
  ): Values[(MergeId[Model], ExternalUpdate)] =
    if (extU.valueTypeId == modelId)
      (extU.valueSrcId → extU) :: Nil
    else
      Nil

  def ToSingleExtUpdate(
    origId: SrcId,
    @by[MergeId[Model]] extUs: Values[ExternalUpdate]
  ): Values[(CombineId[Model], ExternalUpdate)] =
    if (extUs.nonEmpty) {
      val u = extUs.maxBy(_.txId)
      List(origId → u)
    } else Nil

  def ToMergeCacheResponse(
    origId: SrcId,
    cResp: Each[CacheUpdate]
  ): Values[(MergeId[Model], CacheUpdate)] =
    if (cResp.valueTypeId == modelId)
      (cResp.valueSrcId → cResp) :: Nil
    else
      Nil

  def ToSingleCacheResponse(
    origId: SrcId,
    @by[MergeId[Model]] cResps: Values[CacheUpdate]
  ): Values[(CombineId[Model], CacheUpdate)] =
    if (cResps.nonEmpty) {
      val u = cResps.maxBy(_.extOffset)
      List(origId → u)
    } else Nil

  def CreateExternal(
    origId: SrcId,
    @by[ExtSrcId] model: Values[Model],
    @by[CombineId[Model]] externals: Values[ExternalUpdate],
    @by[CombineId[Model]] caches: Values[CacheUpdate]
  ): Values[(SrcId, Model)] =
    if (externals.nonEmpty || caches.nonEmpty)
      (Single.option(externals), Single.option(caches)) match {
        case (Some(e), None) ⇒ decode(e.value)
        case (None, Some(c)) ⇒ decode(c.value)
        case (Some(e), Some(c)) ⇒
          if (e.txId > c.extOffset)
            decode(e.value)
          else if (e.txId < c.extOffset)
            decode(c.value)
          else {
            assert(e.txId == c.extOffset, s"Same offset, different values: $e, $c")
            decode(e.value)
          }
        case _ ⇒ Nil
      }
    else
      model.map(WithPK(_))

  def CreateExternal(
    origId: SrcId,
    @by[CombineId[Model]] externals: Values[ExternalUpdate],
    @by[CombineId[Model]] caches: Values[CacheUpdate]
  ): Values[(SrcId, WriteToKafka)] =
    if (externals.nonEmpty || caches.nonEmpty)
      (Single.option(externals), Single.option(caches)) match {
        case (Some(e), None) ⇒ WriteToKafka(e)
        case (None, Some(c)) ⇒ WriteToKafka(c)
        case (Some(e), Some(c)) ⇒
          if (e.txId > c.extOffset)
            WriteToKafka(e)
          else if (e.txId < c.extOffset)
            WriteToKafka(c)
          else {
            assert(e.txId == c.extOffset, s"Same offset, different values: $e, $c")
            WriteToKafka(e)
          }
        case _ ⇒ Nil
      }
    else
      Nil
}
