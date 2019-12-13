package ee.cone

import ee.cone.c4di.AbstractComponents

import scala.annotation.StaticAnnotation

package object c4proto {
  class protocol(apps: String*) extends StaticAnnotation

  @deprecated type Protocol = AbstractComponents

  type ProtoWriter = com.squareup.wire.ProtoWriter
  type ProtoReader = com.squareup.wire.ProtoReader
  type ProtoAdapter[T] = com.squareup.wire.ProtoAdapter[T]
  type HazyProtoAdapter = Object // so any ProtoAdapter will extend Object
}
