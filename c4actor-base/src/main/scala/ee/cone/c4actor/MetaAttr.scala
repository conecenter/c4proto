package ee.cone.c4actor

import ee.cone.c4proto.{Id, DataCategory, Protocol, protocol}

trait MetaAttr extends Product

case class OrigMetaAttr(orig: Product) extends MetaAttr

case object TxMetaCat extends DataCategory

@protocol(TxMetaCat) object OrigMetaAttrProtocolBase   {
  @Id(0x00ad) case class D_TxTransformNameMeta(
    @Id(0x00ae) clName: String
  )
}