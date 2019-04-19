package ee.cone.c4external

import java.net.CacheResponse

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.UpdatesCat
import ee.cone.c4external.ExternalProtocol.ExternalUpdate
import ee.cone.c4proto.{Id, protocol}
import ee.cone.dbadapter.DBAdapter

object ExternalModel {
  def apply[Model <: Product](cl: Class[Model]): ExternalModel[Model] = ExternalModel(cl.getName)(cl)
}

case class ExternalModel[Model <: Product](clName: String)(val cl: Class[Model])

trait ExtModelsApp {
  def extModels: List[ExternalModel[_ <: Product]] = Nil
}

object ExtSatisfactionApi {
  type ExtSatisfaction = SrcId
  /**
    * How to:
    * $@by[SatisfactionId] satisfactions: Values[Satisfaction]
    *
    * if Satisfaction.status == 'new then request is resolved, and no need to emmit request
    * if Satisfaction.status == 'old then request is resolved, but response is older then specified lifetime, possibly should emmit request
    * if Satisfactions.isEmpty then no responses are ready, need to emmit request
    *
    * All ExtDBRequest should be grouped into ExtDBRequestGroup
    *
    * Values[(RequestGroupId, ExtDBRequestGroup)] =
    * if (requests.nonEmpty)
    * List(externalId.name -> ExtDBRequestGroup(ExtDBRequestType.name, requests)))
    */
  type RequestGroupId = SrcId
}

trait ExtDBRequestType extends Product {
  def uName: String
}

trait ExternalId extends Product {
  def uName: String
  def uid: Int
}

trait ExtDBRequest extends Product {
  def srcId: SrcId
  def externalId: ExternalId
  def rqType: ExtDBRequestType
}

case class ExtDBRequestGroup(extRequestTypeId: String, request: List[ExtDBRequest])

trait ExtDBRqHandlersApp {
  def extDBRequestHandlerFactories: List[ExtDBRequestHandlerFactory[_ <: ExtDBRequest]] = Nil
}

trait ExtDBRequestHandlerFactory[RQ <: ExtDBRequest] {
  def create: DBAdapter ⇒ ExtDBRequestHandler
}

trait ExtDBRequestHandler {
  def handle: List[ExtDBRequest] ⇒ List[CacheResponse]
  def supportedType: ExtDBRequestType
}

trait ExtDBSync {
  def externals: Map[String, Long]
  def upload: List[ExternalUpdate] ⇒ List[(String, Int)]
  def download: List[ExtDBRequestGroup] ⇒ List[CacheResponse]
}

@protocol(UpdatesCat) object ExternalProtocolBase {

  @Id(0x008d) case class ExternalOffset(
    @Id(0x008e) externalName: String,
    @Id(0x008f) offset: String
  )

  @Id(0x0080) case class ExternalUpdate(
    @Id(0x0011) externalUpdateId: String,
    @Id(0x001B) valueSrcId: String,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString,
    @Id(0x001C) flags: Long,
    @Id(0x001A) txId: String
  )

  @Id(0x0085) case class CacheUpdate(
    @Id(0x0011) cacheUpdateSrcId: String,
    @Id(0x001C) valueSrcId: String,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString,
    @Id(0x0089) extOffset: String
  )

}
