package ee.cone.c4actor

import ee.cone.c4di.TypeKey

import scala.collection.immutable.Seq

trait ComponentRegistry {
  def resolve[T](cl: Class[T]): Seq[T]
}

class DeferredSetup(val run: ()=>Unit)

case class StrictTypeKey[T](value: TypeKey)