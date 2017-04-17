
package ee.cone.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

trait Protocol {
  def adapters: List[ProtoAdapter[_] with HasId] = ??? //_<:Object
}

class Id(id: Int) extends StaticAnnotation

case class MetaProp(id: Int, propName: String, resultType: String)

trait HasId {
  def id: Long
  def hasId: Boolean
  def className: String
  def props: List[MetaProp]
}
