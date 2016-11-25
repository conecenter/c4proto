package ee.cone.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

trait Protocol {
  def adapters: List[ProtoAdapter[_] with HasId] = ??? //_<:Object
}

class Id(id: Int) extends StaticAnnotation

trait HasId {
  def id: Long
  def className: String
}

