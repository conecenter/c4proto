package ee.cone.c4actor

import ee.cone.c4proto.{AbstractComponents, Component, TypeKey}
import ee.cone.c4assemble.Single

import scala.collection.immutable.Seq

object ComponentRegistry {
  def isRegistry: Component⇒Boolean = {
    val clName = classOf[ComponentRegistry].getName
    c ⇒ c.out.clName == clName
  }
  def apply(app: AbstractComponents): ComponentRegistry =
    Single(app.components.filter(isRegistry).distinct).create(Seq(app))
      .asInstanceOf[ComponentRegistry]
}

trait ComponentRegistry {
  def resolveKey(key: TypeKey): Seq[Any]
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): Seq[T]
}

abstract class ComponentFactory[T] {
  def forTypes(args: Seq[TypeKey]): Seq[T]
}
