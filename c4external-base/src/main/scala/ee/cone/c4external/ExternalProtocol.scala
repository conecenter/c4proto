package ee.cone.c4external

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.UpdatesCat
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol(UpdatesCat) object ExternalProtocolBase   {

  import ee.cone.c4actor.QProtocol._

  @Id(0x008d) case class ExternalOffset(
    @Id(0x008e) externalId: String,
    @Id(0x008f) offset: String
  )

  @Id(0x0080) case class ExternalUpdates(
    @Id(0x008a) srcId: String,
    @Id(0x001A) txId: String,
    @Id(0x0081) time: Long,
    @Id(0x0082) updates: List[Update]
  )

  @Id(0x0085) case class CacheResponses(
    @Id(0x008b) srcId: String,
    @Id(0x0089) externalOffset: String,
    @Id(0x0087) validUntil: Long,
    @Id(0x0086) reqIds: List[String],
    @Id(0x0088) updates: List[Update]
  )

  @Id(0x0090) case class ExternalTime(
    @Id(0x0091) externalId: String,
    @Id(0x0092) time: Long
  )

  @Id(0x0093) case class ExternalMinValidOffset(
    @Id(0x0094) externalId: String,
    @Id(0x0095) minValidOffset: String
  )


}
