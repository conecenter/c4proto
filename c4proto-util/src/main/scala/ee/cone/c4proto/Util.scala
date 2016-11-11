package ee.cone.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

trait Protocol {
  def adapters: List[ProtoAdapter[_<:Object] with ProtoAdapterWithId] = ???
}

class FindAdapter(list: Seq[Protocol])(
  val byName: Map[String,ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
  list.flatMap(_.adapters).map(a ⇒ a.className → a).toMap
) {
  def apply[M](model: M): ProtoAdapter[M] with ProtoAdapterWithId =
    byName(model.getClass.getName).asInstanceOf[ProtoAdapter[M] with ProtoAdapterWithId]
}

class Id(id: Int) extends StaticAnnotation

trait ProtoAdapterWithId {
  def id: Int
  def className: String
}

