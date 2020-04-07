package ee.cone.c4actor

import ee.cone.c4actor.Types.{FieldId, TypeId, TypeKey}
import ee.cone.c4proto.{DataCategory, MetaProp, ProtoOrigMeta}

trait MetaInformation {
  def shortName: Option[String]
  def typeKey: TypeKey
  def metaAttrs: List[AbstractMetaAttr]
  def annotations: List[String] // Annotations other then @Id, @Meta, @ShortName
}

case class FieldMeta(
  id: FieldId,
  name: String,
  shortName: Option[String],
  typeKey: TypeKey,
  metaAttrs: List[AbstractMetaAttr],
  annotations: List[String]
) extends MetaInformation {
  lazy val typeAlias: String = typeKey.fullAlias
  lazy val metaProp: MetaProp = MetaProp(id.toInt, name, shortName, typeAlias, typeKey)
}

trait GeneralOrigMeta extends MetaInformation {
  def id: Option[TypeId]
  def categories: List[DataCategory]
  def cl: Class[_]
  def fieldsMeta: List[FieldMeta]
  def inherits: List[TypeKey]
}

trait OrigMeta[Orig <: Product] extends GeneralOrigMeta with ProtoOrigMeta {
  def cl: Class[Orig] = typeKey.cl.asInstanceOf[Class[Orig]]
  def replaces: Option[TypeKey]
  lazy val props: List[MetaProp] = fieldsMeta.map(_.metaProp)
}
