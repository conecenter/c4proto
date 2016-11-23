package ee.cone.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

trait Protocol {
  def adapters: List[ProtoAdapter[_<:Object] with ProtoAdapterWithId] = ???
}

class FindAdapter(list: Seq[Protocol])(
  adapters: Seq[ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
    list.flatMap(_.adapters)
)(
  val byName: Map[String,ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
    adapters.map(a ⇒ a.className → a).toMap,
  val byId: Map[Long,ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
    adapters.map(a ⇒ a.id → a).toMap,
  val nameById: Map[Long,String] =
    adapters.map(a ⇒ a.id → a.className).toMap
) {
  def apply[M](model: M): ProtoAdapter[M] with ProtoAdapterWithId =
    byClass(model.getClass.asInstanceOf[Class[M]])
  def byClass[M](cl: Class[M]): ProtoAdapter[M] with ProtoAdapterWithId =
    byName(cl.getName).asInstanceOf[ProtoAdapter[M] with ProtoAdapterWithId]
}

class Id(id: Int) extends StaticAnnotation

trait ProtoAdapterWithId {
  def id: Long
  def className: String
}

