package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.{ArgAdapter, HasId}

import scala.collection.immutable.Map

abstract class DefaultArgument[Value](val value: Value)
abstract class ArgAdapterFactory[T](val wrap: (()=>ProtoAdapter[Any])=>ArgAdapter[_])
abstract class LazyArgAdapterFactory[T](val wrap: (()=>ProtoAdapter[Any])=>ArgAdapter[_])

object ArgTypes {
  type LazyOption[T] = Option[T]
  type LazyList[T] = List[T]
}

case object QAdapterRegistryKey extends SharedComponentKey[QAdapterRegistry]

trait QAdapterRegistry {
  def byName: Map[String, ProtoAdapter[Product] with HasId]
  def byId: Map[Long, ProtoAdapter[Product] with HasId]
}