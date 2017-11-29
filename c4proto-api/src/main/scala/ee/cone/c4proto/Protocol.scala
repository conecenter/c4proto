
package ee.cone.c4proto

import java.nio.charset.StandardCharsets.UTF_8

import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.HiddenC4Annotations.{c4component, listed}
import okio.ByteString

import scala.annotation.StaticAnnotation

object HiddenC4Annotations {
  class c4component
  class listed
}

@c4component @listed abstract class PBAdapters {
  def adapters: List[ProtoAdapter[_] with HasId]
}

class protocol extends StaticAnnotation

case class Id(id: Int) extends StaticAnnotation

case class MetaProp(id: Int, propName: String, resultType: String)

trait HasId {
  def id: Long
  def hasId: Boolean
  def className: String
  def props: List[MetaProp]
}

object ToByteString {
  def apply(data: Array[Byte]): ByteString = ByteString.of(data,0,data.length)
  def apply(v: String): ByteString = apply(v.getBytes(UTF_8))
}