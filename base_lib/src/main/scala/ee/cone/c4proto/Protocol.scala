
package ee.cone.c4proto

import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4di.TypeKey
import okio.ByteString

import collection.immutable.Seq
import scala.annotation.StaticAnnotation

case class Id(id: Long) extends StaticAnnotation

case class ShortName(name: String) extends StaticAnnotation

class GenLens extends StaticAnnotation

//  override def toString: String =
//    s"TypeKey(${if (clName.endsWith(alias)) clName else s"$clName/$alias"}${if (args.isEmpty) "" else args.map(_.toString).mkString("[",", ", "]")})"

case class MetaProp(id: Int, propName: String, propShortName: Option[String], resultType: String, typeProp: TypeKey)

trait ProtoOrigMeta {
  def id: Option[Long]
  def categories: List[DataCategory]
  def cl: Class[_]
  def shortName: Option[String]
  def metaProps: List[MetaProp]
}

trait HasId {
  def protoOrigMeta: ProtoOrigMeta
  def id: Long = protoOrigMeta.id.getOrElse(throw new Exception("This orig has no Id"))
  def hasId: Boolean = protoOrigMeta.id.nonEmpty
  lazy val className: String = protoOrigMeta.cl.getName

  @deprecated("Deprecated, use OrigMeta[Orig].categories", "07/04/20")
  def categories: List[DataCategory] = protoOrigMeta.categories
  @deprecated("Deprecated, use OrigMeta[Orig].cl", "07/04/20")
  def cl: Class[_] = protoOrigMeta.cl
  @deprecated("Deprecated, use OrigMeta[Orig].shortName", "07/04/20")
  def shortName: Option[String] = protoOrigMeta.shortName
  @deprecated("Deprecated, use OrigMeta[Orig].fieldsMeta", "07/04/20")
  def props: List[MetaProp] = protoOrigMeta.metaProps
}

object ToByteString {
  def apply(data: Array[Byte]): ByteString = ByteString.of(data,0,data.length)
  def apply(v: String): ByteString = apply(v.getBytes(UTF_8))
}

class replaceBy[T](factory: Object) extends StaticAnnotation

abstract class ArgAdapter[Value] {
  def encodedSizeWithTag (tag: Int, value: Value): Int
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: Value): Unit
  def defaultValue: Value
  def decodeReduce(reader: ProtoReader, prev: Value): Value
  def decodeFix(prev: Value): Value
}

object FieldEncoding {
  val LENGTH_DELIMITED = com.squareup.wire.FieldEncoding.LENGTH_DELIMITED
}

trait GeneralProtoChanger {
  def entityClass: Class[_]
  def attributeClass: Class[_]
}
class ProtoChanger[E,A](
  val entityClass: Class[E],
  val attributeClass: Class[A],
  val changePrimaryKey: (E,A)=>E
) extends GeneralProtoChanger
