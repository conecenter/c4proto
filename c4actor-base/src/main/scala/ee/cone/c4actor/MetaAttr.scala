package ee.cone.c4actor

import ee.cone.c4proto.{Id, Protocol, protocol}

trait MetaAttr extends Product

case class OrigMetaAttr(orig: Product) extends MetaAttr

@protocol object OrigMetaAttrProtocol extends Protocol {
  @Id(0x00ad) case class TxTransformNameMeta(
    @Id(0x00ae) clName: String
  )
}