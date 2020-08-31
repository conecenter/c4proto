package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, FieldId, TypeId, TypeKey}
import ee.cone.c4proto.{DataCategory, MetaProp, ProtoOrigMeta}

trait MetaInformation {
  def shortName: Option[String]
  def typeKey: TypeKey
  def metaAttrs: List[AbstractMetaAttr]
  def annotations: List[String] // Annotations other than @Id, @Meta, @ShortName
}

trait FieldMeta extends MetaInformation {
  def id: FieldId
  def name: String
  def shortName: Option[String]
  def typeKey: TypeKey
  def metaAttrs: List[AbstractMetaAttr]
  def annotations: List[String]
  def typeAlias: String
  @deprecated("Deprecated, used for compatibility with HasId", "07/04/20")
  def metaProp: MetaProp
}

trait GeneralOrigMeta extends MetaInformation with ProtoOrigMeta {
  def isMaster: Boolean
  def masterComment: Option[String]
  def id: Option[TypeId]
  def categories: List[DataCategory]
  def cl: Class[_]
  def fieldsMeta: List[FieldMeta]
  def field(id: FieldId): FieldMeta
  def inherits: List[TypeKey]
  def replaces: Option[TypeKey] // @replaceBy
  @deprecated("Deprecated, used for compatibility with HasId", "07/04/20")
  lazy val metaProps: List[MetaProp] = fieldsMeta.map(_.metaProp)
}

trait OrigMeta[Orig <: Product] extends GeneralOrigMeta {
  // If you want to add more lazy vals here separate api and impl
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
