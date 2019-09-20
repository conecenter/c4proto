package ee.cone.c4actor

import ee.cone.c4proto.{Id, protocol}

import scala.annotation.StaticAnnotation

trait AbstractMetaAttr extends Product

case class Meta(meta: AbstractMetaAttr*) extends StaticAnnotation

case class MetaAttr(orig: Product) extends AbstractMetaAttr

@protocol object MetaAttrProtocolBase   {
  @Id(0x00ad) case class D_TxTransformNameMeta(
    @Id(0x00ae) clName: String
  )
}