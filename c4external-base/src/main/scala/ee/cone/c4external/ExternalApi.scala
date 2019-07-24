package ee.cone.c4external

import ee.cone.c4actor.UpdatesCat
import ee.cone.c4external.ExternalProtocol.S_ExternalUpdate
import ee.cone.c4proto.{Id, protocol}

object ExternalModel {
  def apply[Model <: Product](cl: Class[Model]): ExternalModel[Model] = ExternalModel(cl.getName)(cl)
}

case class ExternalModel[Model <: Product](clName: String)(val cl: Class[Model])

trait ExtModelsApp {
  def extModels: List[ExternalModel[_ <: Product]] = Nil
}

trait ExternalId extends Product {
  def uName: String
  def uid: Int
}

trait ExtDBSync {
  def upload: List[S_ExternalUpdate] ⇒ List[(String, Int)]
}

@protocol object ExternalProtocolBase {

  @Id(0x008d) case class S_ExternalOffset(
    @Id(0x008e) externalName: String,
    @Id(0x008f) offset: String
  )

  @Id(0x0080) case class S_ExternalUpdate(
    @Id(0x0011) externalUpdateId: String,
    @Id(0x001B) valueSrcId: String,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString,
    @Id(0x001C) flags: Long,
    @Id(0x001A) txId: String
  )

  @Id(0x0085) case class S_CacheUpdate(
    @Id(0x0011) cacheUpdateSrcId: String,
    @Id(0x001C) valueSrcId: String,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString,
    @Id(0x0089) extOffset: String
  )

}
