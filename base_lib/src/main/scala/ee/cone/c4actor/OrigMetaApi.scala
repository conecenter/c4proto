package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, FieldId, TypeId, TypeKey}
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

trait GeneralOrigMeta extends MetaInformation with ProtoOrigMeta {
  def id: Option[TypeId]
  def categories: List[DataCategory]
  def cl: Class[_]
  def fieldsMeta: List[FieldMeta]
  def field(id: FieldId): FieldMeta
  def inherits: List[TypeKey]
  def replaces: Option[TypeKey] // @replaceBy
  lazy val metaProps: List[MetaProp] = fieldsMeta.map(_.metaProp)
}

trait OrigMeta[Orig <: Product] extends GeneralOrigMeta {
  lazy val cl: Class[Orig] = replaces.getOrElse(typeKey).cl.asInstanceOf[Class[Orig]]
  lazy val fieldsById: Map[FieldId, FieldMeta] = fieldsMeta.map(field => field.id -> field).toMap
  def field(id: FieldId): FieldMeta = fieldsById.getOrElse(id, throw new Exception(s"${cl.getSimpleName} doesn't have ${f"0x$id%04X"} field"))
}

trait OrigMetaRegistry {
  def all: List[GeneralOrigMeta]
  def byName: Map[ClName, OrigMeta[Product]]
  def getByCl[Orig <: Product](cl: Class[Orig]): OrigMeta[Orig]
  def byId: Map[TypeId, OrigMeta[Product]]
  def getById[Orig <: Product](id: TypeId): OrigMeta[Orig]
  def byTypeKey: Map[TypeKey, OrigMeta[Product]]
}
