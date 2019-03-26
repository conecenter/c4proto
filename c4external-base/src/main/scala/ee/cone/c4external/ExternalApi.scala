package ee.cone.c4external

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.UpdatesCat
import ee.cone.c4external.ExternalProtocol.{CacheResponses, ExternalUpdates}
import ee.cone.c4proto.{Id, protocol}
import ee.cone.dbadapter.DBAdapter

object ExtSatisfactionApi {
  type ExtSatisfaction = SrcId
  /**
    * How to:
    *   @by[SatisfactionId] satisfactions: Values[Satisfaction]
    *
    *   if Satisfaction.status == 'new then request is resolved, and no need to emmit request
    *   if Satisfaction.status == 'old then request is resolved, but response is older then specified lifetime, possibly should emmit request
    *   if Satisfactions.isEmpty then no responses are ready, need to emmit request
    *
    *   All ExtDBRequest should be grouped into ExtDBRequestGroup
    *
    *   Values[(RequestGroupId, ExtDBRequestGroup)] =
    *     if (requests.nonEmpty)
    *       List(externalId.name -> ExtDBRequestGroup(ExtDBRequestType.name, requests)))
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
  def handle: List[ExtDBRequest] ⇒ List[CacheResponses]
  def supportedType: ExtDBRequestType
}

trait ExtDBSync {
  def externals: Map[String, Long]
  def upload: List[ExternalUpdates] ⇒ List[(String, Int)]
  def download: List[ExtDBRequestGroup] ⇒ List[CacheResponses]
}

@protocol(UpdatesCat) object ExternalProtocolBase {

  import ee.cone.c4actor.QProtocol._

  @Id(0x008d) case class ExternalOffset(
    @Id(0x008e) externalName: String,
    @Id(0x008f) offset: String
  )

  @Id(0x0080) case class ExternalUpdates(
    @Id(0x008a) srcId: String,
    @Id(0x001A) txId: String,
    @Id(0x0096) valueTypeId: Long,
    @Id(0x0081) time: Long,
    @Id(0x0097) updates: List[Update]
  )

  @Id(0x0085) case class CacheResponses(
    @Id(0x008b) srcId: String,
    @Id(0x0089) extOffset: String,
    @Id(0x0087) validUntil: Long,
    @Id(0x0086) reqIds: List[String],
    @Id(0x0088) updates: List[Update]
  )

  @Id(0x0090) case class ExternalTime(
    @Id(0x0091) externalName: String,
    @Id(0x0092) time: Long
  )

  @Id(0x0093) case class ExternalMinValidOffset(
    @Id(0x0094) externalName: String,
    @Id(0x0095) minValidOffset: String
  )
}
