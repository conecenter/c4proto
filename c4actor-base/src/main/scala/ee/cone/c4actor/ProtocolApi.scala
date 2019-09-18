package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4proto.ArgAdapter

abstract class DefaultArgument[Value](val value: Value)
abstract class ProtoAdapterHolder[T](val value: ProtoAdapter[T])
abstract class ArgAdapterFactory[T](val wrap: (()⇒ProtoAdapter[Any])⇒ArgAdapter[_])
abstract class LazyArgAdapterFactory[T](val wrap: (()⇒ProtoAdapter[Any])⇒ArgAdapter[_])

object ArgTypes {
  type LazyOption[T] = Option[T]
  type LazyList[T] = List[T]
}
