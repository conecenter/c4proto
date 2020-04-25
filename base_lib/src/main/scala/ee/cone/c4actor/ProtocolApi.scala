package ee.cone.c4actor

import ee.cone.c4di.TypeKey
import ee.cone.c4proto._

import scala.collection.immutable.Map

abstract class GeneralDefaultArgument {
  def value: Any
}
abstract class DefaultArgument[Value](val value: Value) extends GeneralDefaultArgument
abstract class ArgAdapterFactory(val key: TypeKey, val wrap: (()=>ProtoAdapter[Any])=>ArgAdapter[_])
abstract class LazyArgAdapterFactory(val key: TypeKey, val wrap: (()=>ProtoAdapter[Any])=>ArgAdapter[_])

object ArgTypes {
  type LazyOption[T] = Option[T]
  type LazyList[T] = List[T]
}

trait QAdapterRegistry {
  def byName: Map[String, ProtoAdapter[Product] with HasId]
  def byId: Map[Long, ProtoAdapter[Product] with HasId]
}