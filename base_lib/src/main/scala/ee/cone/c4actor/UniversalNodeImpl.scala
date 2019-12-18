package ee.cone.c4actor

import ee.cone.c4proto._
import ee.cone.c4di.c4

@c4("RichDataCompApp") class UniversalNodeFactoryImpl extends UniversalNodeFactory {
  def node(props: List[UniversalProp]): UniversalNode = UniversalNodeImpl(props)
  def prop[T <: Object](tag: Int, value: T, adapter: ProtoAdapter[T]): UniversalProp =
    UniversalPropImpl(tag,value)(adapter)
}

case class UniversalNodeImpl(props: List[UniversalProp]) extends UniversalNode

case class UniversalPropImpl[T<:Object](tag: Int, value: T)(adapter: ProtoAdapter[T]) extends UniversalProp {
  def encodedSize: Int = adapter.encodedSizeWithTag(tag, value)
  def encode(writer: ProtoWriter): Unit = adapter.encodeWithTag(writer, tag, value)
  def encodedValue: Array[Byte] = adapter.encode(value)
}

@c4("RichDataCompApp") class UniversalProtoAdapter extends ProtoAdapter[UniversalNode](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalNode]) {
  def encodedSize(value: UniversalNode): Int =
    value.props.map(_.encodedSize).sum
  def encode(writer: ProtoWriter, value: UniversalNode): Unit =
    value.props.foreach(_.encode(writer))
  def decode(reader: ProtoReader): UniversalNode = throw new Exception("not implemented")
}
