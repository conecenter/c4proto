
package ee.cone.c4proto

import java.nio.charset.StandardCharsets.UTF_8

import com.squareup.wire.ProtoAdapter
import okio.ByteString

import scala.annotation.StaticAnnotation

trait Protocol {
  def adapters: List[ProtoAdapter[_] with HasId] = ??? //_<:Object
}

trait OrigCategory extends Product

case class Cat(category: OrigCategory*) extends StaticAnnotation

case class Id(id: Int) extends StaticAnnotation

case class MetaProp(id: Int, propName: String, resultType: String)

trait HasId {
  def id: Long
  def hasId: Boolean
  def categories: List[OrigCategory]
  def className: String
  def props: List[MetaProp]
}

object ToByteString {
  def apply(data: Array[Byte]): ByteString = ByteString.of(data,0,data.length)
  def apply(v: String): ByteString = apply(v.getBytes(UTF_8))
}