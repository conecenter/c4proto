package ee.cone.c4actor

import com.squareup.wire.{FieldEncoding, ProtoAdapter, ProtoReader, ProtoWriter}
import ee.cone.c4proto.c4

trait UniversalNode {
  def props: List[UniversalProp]
}

case class UniversalNodeImpl(props: List[UniversalProp]) extends UniversalNode

case class UniversalDeleteImpl(props: List[UniversalProp]) extends UniversalNode

sealed trait UniversalProp {
  def tag: Int
  def value: Object
  def encodedValue: Array[Byte]
  def encodedSize: Int
  def encode(writer: ProtoWriter): Unit
}

case class UniversalPropImpl[T<:Object](tag: Int, value: T)(adapter: ProtoAdapter[T]) extends UniversalProp {
  def encodedSize: Int = adapter.encodedSizeWithTag(tag, value)
  def encode(writer: ProtoWriter): Unit = adapter.encodeWithTag(writer, tag, value)
  def encodedValue: Array[Byte] = adapter.encode(value)
}

object UniversalProtoAdapter extends UniversalProtoAdapter
@c4("RichDataCompApp") class UniversalProtoAdapter extends ProtoAdapter[UniversalNode](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalNode]) {
  def encodedSize(value: UniversalNode): Int =
    value.props.map(_.encodedSize).sum
  def encode(writer: ProtoWriter, value: UniversalNode): Unit =
    value.props.foreach(_.encode(writer))
  def decode(reader: ProtoReader): UniversalNode = throw new Exception("not implemented")
}

object UniversalDeleteProtoAdapter extends ProtoAdapter[UniversalDeleteImpl](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalDeleteImpl]) {
  def encodedSize(value: UniversalDeleteImpl): Int = throw new Exception("Can't be called")
  def encode(writer: ProtoWriter, value: UniversalDeleteImpl): Unit = throw new Exception("Can't be called")
  def decode(reader: ProtoReader): UniversalDeleteImpl = throw new Exception("Can't be called")
}

