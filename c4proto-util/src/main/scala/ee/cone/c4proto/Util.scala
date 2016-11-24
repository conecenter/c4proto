package ee.cone.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

import AdapterTypes._

trait Protocol {
  def adapters: List[ProtoAdapterWithId[_<:Object]] = ???
}

class FindAdapter(list: Seq[Protocol])(
  adapters: Seq[ProtoAdapterWithId[_<:Object]] = list.flatMap(_.adapters)
)(
  byName: Map[String,ProtoAdapterWithId[_<:Object]] =
    adapters.map(a ⇒ a.className → a).toMap,
  val byId: Map[Long,ProtoAdapterWithId[_<:Object]] =
    adapters.map(a ⇒ a.id → a).toMap,
  val nameById: Map[Long,String] =
    adapters.map(a ⇒ a.id → a.className).toMap
) {
  def apply[M](model: M): ProtoAdapterWithId[M] =
    byClass(model.getClass.asInstanceOf[Class[M]])
  def byClass[M](cl: Class[M]): ProtoAdapterWithId[M] =
    byName(cl.getName).asInstanceOf[ProtoAdapterWithId[M]]
}

class Id(id: Int) extends StaticAnnotation

trait HasId {
  def id: Long
  def className: String
}

object AdapterTypes {
  type ProtoAdapterWithId[M] = ProtoAdapter[M] with HasId
}

