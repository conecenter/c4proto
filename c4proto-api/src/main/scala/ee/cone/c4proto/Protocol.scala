
package ee.cone.c4proto

import java.nio.charset.StandardCharsets.UTF_8

import com.squareup.wire.{ProtoAdapter, ProtoReader, ProtoWriter}
import okio.ByteString
import collection.immutable.Seq
import scala.annotation.StaticAnnotation

trait Protocol extends AbstractComponents

case class Id(id: Int) extends StaticAnnotation

case class ShortName(name: String) extends StaticAnnotation

class GenLens extends StaticAnnotation

case class TypeKey(clName: String, alias: String, args: List[TypeKey])
case class MetaProp(id: Int, propName: String, propShortName: Option[String], resultType: String, typeProp: TypeKey)

trait HasId {
  def id: Long
  def hasId: Boolean
  def categories: List[DataCategory]
  def className: String
  def cl: Class[_]
  def shortName: Option[String]
  def props: List[MetaProp]
}

object ToByteString {
  def apply(data: Array[Byte]): ByteString = ByteString.of(data,0,data.length)
  def apply(v: String): ByteString = apply(v.getBytes(UTF_8))
}

class c4component extends StaticAnnotation

class replaceBy[T](factory: Object) extends StaticAnnotation

abstract class ArgAdapter[Value] {
  def encodedSizeWithTag (tag: Int, value: Value): Int
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: Value): Unit
  def defaultValue: Value
  def decodeReduce(reader: ProtoReader, prev: Value): Value
  def decodeFix(prev: Value): Value
}

trait AbstractComponents {
  def components: Seq[Component]
}
class Component(val out: TypeKey, val in: Seq[TypeKey], val create: Seq[Object]=>Object) extends AbstractComponents {
  def components: Seq[Component] = Seq(this)
}
abstract class Components(componentsList: Seq[AbstractComponents]) extends AbstractComponents {
  def components: Seq[Component] = componentsList.flatMap(_.components)
}
trait ComponentsApp {
  def components: List[Component] = Nil
}