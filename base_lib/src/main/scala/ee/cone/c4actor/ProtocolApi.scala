package ee.cone.c4actor

import ee.cone.c4di.TypeKey
import ee.cone.c4proto._

import scala.collection.immutable.Map

abstract class HazyDefaultArgument {
  def value: Any
}
abstract class DefaultArgument[Value](val value: Value) extends HazyDefaultArgument
abstract class ArgAdapterFactory(val key: TypeKey, val wrap: (()=>ProtoAdapter[Any])=>ArgAdapter[_])
abstract class LazyArgAdapterFactory(val key: TypeKey, val wrap: (()=>ProtoAdapter[Any])=>ArgAdapter[_])

object ArgTypes {
  type LazyOption[T] = Option[T]
  type LazyList[T] = List[T]
}

case object QAdapterRegistryKey extends SharedComponentKey[QAdapterRegistry]

trait QAdapterRegistry {
  def byName: Map[String, ProtoAdapter[Product] with HasId]
  def byId: Map[Long, ProtoAdapter[Product] with HasId]
}