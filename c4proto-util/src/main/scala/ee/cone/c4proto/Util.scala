package ee.cone.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

import AdapterTypes._

trait Protocol {
  def adapters: List[ProtoAdapterWithId[_<:Object]] = ???
}

class Id(id: Int) extends StaticAnnotation

trait HasId {
  def id: Long
  def className: String
}

object AdapterTypes {
  type ProtoAdapterWithId[M] = ProtoAdapter[M] with HasId
}

