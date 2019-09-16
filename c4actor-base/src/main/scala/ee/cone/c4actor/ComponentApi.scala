package ee.cone.c4actor

import ee.cone.c4proto.{AbstractComponents, Component, TypeKey}
import ee.cone.c4assemble.Single

import scala.collection.immutable.Seq

object ComponentRegistry {
  def isRegistry: Component⇒Boolean = {
    val key = TypeKey(classOf[ComponentRegistry].getName)
    c ⇒ c.out == key
  }
  def apply(app: AbstractComponents): ComponentRegistry =
    Single(app.components.filter(isRegistry).distinct).create(Seq(app))
      .asInstanceOf[ComponentRegistry]

}

trait ComponentRegistry {
  def resolve(key: TypeKey): Seq[Any]
  def resolveSingle(key: TypeKey): Any
  def resolveSingle[T](cl: Class[T]): T
}

abstract class ComponentFactory[T<:Product](theClass: Class[T]) {
  def forTypes(args: Seq[TypeKey]): Seq[T]
}
