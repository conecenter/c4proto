package ee.cone.c4actor

import ee.cone.c4proto.{Id, Protocol, protocol}

trait WithExternalProtocolApp extends ProtocolsApp {
  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
}

@protocol(UpdatesCat) object ExternalProtocol extends Protocol {

  @Id(0x0080) case class ExternalUpdate(
    @Id(0x0081) srcId: String,
    @Id(0x0082) valueTypeId: Long,
    @Id(0x0083) value: okio.ByteString,
    @Id(0x0084) txRefId: String
  )

  @Id(0x0085) case class CacheUpdate(
    @Id(0x0086) srcId: String,
    @Id(0x0087) valueTypeId: Long,
    @Id(0x0088) value: okio.ByteString,
    @Id(0x0089) offset: String
  )

}
