package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.{AbstractComponents, Component, ComponentCreator, TypeKey}

import scala.collection.immutable.Seq

object ComponentRegistry {
  def toRegistry: Component=>Seq[ComponentCreator] = {
    val clName = classOf[AbstractComponents].getName;
    { case c: ComponentCreator =>
      c.in match {
        case Seq(inKey) if inKey.clName == clName => Seq(c)
        case _ => Nil
      }
    }
  }
  def apply(app: AbstractComponents): ComponentRegistry =
    Single(Single(app.components.flatMap(toRegistry).distinct).create(Seq(app)))
      .asInstanceOf[ComponentRegistry]
}

trait ComponentRegistry {
  def resolveKey(key: TypeKey): DeferredSeq[Any]
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T]
  def components: Seq[ComponentCreator]
}

trait DeferredSeq[+T] {
  def value: Seq[T]
}

case class StrictTypeKey[T](value: TypeKey)