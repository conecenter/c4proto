package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, FieldId, TypeId, TypeKey}
import ee.cone.c4di.c4
import ee.cone.c4proto.MetaProp

object TypeKeyFullAlias {
  def apply(typeKey: TypeKey): String =
    s"${typeKey.alias}${if (typeKey.args.isEmpty) "" else s"[${typeKey.args.map(apply).mkString(", ")}]"}"
}

case class FieldMetaImpl(
  id: Long,
  name: String,
  shortName: Option[String],
  typeKey: TypeKey,
  metaAttrs: List[AbstractMetaAttr],
  annotations: List[String]
) extends FieldMeta {
  lazy val typeAlias: String = TypeKeyFullAlias(typeKey)
  lazy val metaProp: MetaProp = MetaProp(Math.toIntExact(id), name, shortName, typeAlias, typeKey)
}

@c4("ProtoApp") final class OrigMetaRegistryImpl(val all: List[GeneralOrigMeta]) extends OrigMetaRegistry {
  val nonGeneral: List[OrigMeta[Product]] = all.distinctBy(_.typeKey).asInstanceOf[List[OrigMeta[Product]]]
  val byName: Map[ClName, OrigMeta[Product]] = CheckedMap(nonGeneral.map(meta => meta.cl.getName -> meta))
  val byId: Map[TypeId, OrigMeta[Product]] = CheckedMap(nonGeneral.filter(_.id.nonEmpty).map(meta => meta.id.get -> meta))
  val byTypeKey: Map[TypeKey, OrigMeta[Product]] = CheckedMap(nonGeneral.map(meta => meta.typeKey -> meta))
  def getByCl[Orig <: Product](cl: Class[Orig]): OrigMeta[Orig] =
    byName.getOrElse(cl.getName, throw new Exception(s"OrigMetaRegistry doesn't contain ${cl.getName}")).asInstanceOf[OrigMeta[Orig]]
  def getById[Orig <: Product](id: TypeId): OrigMeta[Orig] =
    byId.getOrElse(id, throw new Exception(s"OrigMetaRegistry doesn't contain ${f"0x$id%04X"}")).asInstanceOf[OrigMeta[Orig]]
}
