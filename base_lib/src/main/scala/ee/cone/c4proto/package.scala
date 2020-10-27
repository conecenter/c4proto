package ee.cone

import scala.annotation.StaticAnnotation

package object c4proto {
  class protocol(apps: String*) extends StaticAnnotation

  /**
   * Defines that orig with this annotation can't have life given to it and can't be mortal
   */
  class Master(comment: String = "") extends StaticAnnotation

  type ProtoWriter = com.squareup.wire.ProtoWriter
  type ProtoReader = com.squareup.wire.ProtoReader
  type ProtoAdapter[T] = com.squareup.wire.ProtoAdapter[T]
  type GeneralProtoAdapter = Object // so any ProtoAdapter will extend Object
}
