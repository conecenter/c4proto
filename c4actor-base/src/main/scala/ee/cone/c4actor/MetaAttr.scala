package ee.cone.c4actor

import ee.cone.c4proto.{Id, OrigCategory, Protocol, protocol}

trait MetaAttr extends Product

case class OrigMetaAttr(orig: Product) extends MetaAttr

case object TxMetaOrigCat extends OrigCategory

@protocol(TxMetaOrigCat) object OrigMetaAttrProtocol extends Protocol {
  @Id(0x00ad) case class TxTransformNameMeta(
    @Id(0x00ae) clName: String
  )
}