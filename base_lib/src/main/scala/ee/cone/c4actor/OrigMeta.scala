package ee.cone.c4actor

import ee.cone.c4actor.Types.{FieldId, TypeId, TypeKey}
import ee.cone.c4proto.{DataCategory, MetaProp}

trait MetaInformation {
  def shortName: Option[String]
  def typeKey: TypeKey
  def metaAttrs: List[MetaAttr]
  def annotations: List[String] // Annotations other then @Id, @Meta, @ShortName
}

case class FieldMeta(
  id: FieldId,
  name: String,
  shortName: Option[String],
  typeKey: TypeKey,
  metaAttrs: List[MetaAttr],
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
}

case class OrigMeta[Orig <: Product](
  id: Option[TypeId],
  categories: List[DataCategory],
  fieldsMeta: List[FieldMeta],
  shortName: Option[String],
  typeKey: TypeKey,
  metaAttrs: List[MetaAttr],
  annotations: List[String],
) extends GeneralOrigMeta {
  def cl: Class[Orig] = typeKey.cl.asInstanceOf[Class[Orig]]
}
