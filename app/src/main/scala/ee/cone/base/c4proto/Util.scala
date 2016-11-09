package ee.cone.base.c4proto

import com.squareup.wire.ProtoAdapter

import scala.annotation.StaticAnnotation

trait Schema {
  def adapters: List[ProtoAdapter[_<:Object] with ProtoAdapterWithId] = ???
}

class FindAdapter(schemaList: Schema*)(
  val byName: Map[String,ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
  schemaList.flatMap(_.adapters).map(a ⇒ a.className → a).toMap
) {
  def apply[M](model: M) =
    byName(model.getClass.getName).asInstanceOf[ProtoAdapter[M]]
}

class Id(id: Int) extends StaticAnnotation

trait ProtoAdapterWithId {
  def id: Int
  def className: String
}

