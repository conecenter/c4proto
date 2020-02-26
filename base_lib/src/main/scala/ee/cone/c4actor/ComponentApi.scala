package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.{AbstractComponents, Component, TypeKey}

import scala.collection.immutable.Seq

object ComponentRegistry {
  def isRegistry: Component=>Boolean = {
    val clName = classOf[AbstractComponents].getName
    c => c.in match {
      case Seq(inKey) if inKey.clName == clName => true
      case _ => false
    }
  }
  def apply(app: AbstractComponents): ComponentRegistry =
    Single(Single(app.components.filter(isRegistry).distinct).create(Seq(app)))
      .asInstanceOf[ComponentRegistry]
}

trait ComponentRegistry {
  def resolveKey(key: TypeKey): DeferredSeq[Any]
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T]
  def components: Seq[Component]
}

trait DeferredSeq[+T] {
  def value: Seq[T]
}