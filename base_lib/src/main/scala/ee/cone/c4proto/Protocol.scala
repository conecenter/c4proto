
package ee.cone.c4proto

import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4di.TypeKey
import okio.ByteString

import collection.immutable.Seq
import scala.annotation.StaticAnnotation

case class Id(id: Int) extends StaticAnnotation

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

@deprecated("Deprecated, use OrigMeta[Orig]", "07/04/20")
trait HasId {
  def protoOrigMeta: ProtoOrigMeta
  def id: Long = protoOrigMeta.id.getOrElse(throw new Exception("This orig has no Id"))
  def hasId: Boolean = protoOrigMeta.id.nonEmpty
  def categories: List[DataCategory] = protoOrigMeta.categories
  lazy val className: String = protoOrigMeta.cl.getName
  def cl: Class[_] = protoOrigMeta.cl
  def shortName: Option[String] = protoOrigMeta.shortName
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