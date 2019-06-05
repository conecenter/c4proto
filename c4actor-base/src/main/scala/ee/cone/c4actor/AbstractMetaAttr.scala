package ee.cone.c4actor

import ee.cone.c4proto.{Id, DataCategory, Protocol, protocol}

trait AbstractMetaAttr extends Product

case class MetaAttr(orig: Product) extends AbstractMetaAttr

case object TxMetaCat extends DataCategory

@protocol(TxMetaCat) object MetaAttrProtocolBase   {
  @Id(0x00ad) case class D_TxTransformNameMeta(
    @Id(0x00ae) clName: String
  )
}