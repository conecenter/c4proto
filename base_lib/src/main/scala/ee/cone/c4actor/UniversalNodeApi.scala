package ee.cone.c4actor

import ee.cone.c4proto._

trait UniversalNode {
  def props: List[UniversalProp]
}

trait UniversalProp {
  def tag: Int
  def value: Object
  def encodedValue: Array[Byte]
  def encodedSize: Int
  def encode(writer: ProtoWriter): Unit
}

trait UniversalNodeFactory {
  def node(props: List[UniversalProp]): UniversalNode
  def prop[T<:Object](tag: Int, value: T, adapter: ProtoAdapter[T]): UniversalProp
}