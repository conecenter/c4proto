package ee.cone.c4proto

import com.squareup.wire.{FieldEncoding, ProtoAdapter, ProtoReader, ProtoWriter}

import scala.annotation.tailrec

case class UniversalNode(props: List[UniversalProp])
sealed trait UniversalProp {
  def encodedSize: Int
  def encode(writer: ProtoWriter): Unit
}

case class UniversalPropImpl[T](tag: Int, value: T)(adapter: ProtoAdapter[T]) extends UniversalProp {
  def encodedSize: Int = adapter.encodedSizeWithTag(tag, value)
  def encode(writer: ProtoWriter): Unit = adapter.encodeWithTag(writer, tag, value)
}

object UniversalProtoAdapter extends ProtoAdapter[UniversalNode](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalNode]) {
  def encodedSize(value: UniversalNode): Int =
    value.props.map(_.encodedSize).sum
  def encode(writer: ProtoWriter, value: UniversalNode): Unit =
    value.props.foreach(_.encode(writer))
  def decode(reader: ProtoReader): UniversalNode = throw new Exception("not implemented")
}

class IndentedParser(splitter: Char, propTypeRegistry: String⇒LinesToUniversalProp) {
  def parse(data: okio.ByteString): UniversalNode = {
    val lines = data.utf8().split('\n').toList
    if(lines.last != "") throw new Exception
    UniversalNode(parseProps(splitter, lines.init, Nil))
  }
  //@tailrec final
  def parseProps(splitter: Char, lines: List[String], res: List[UniversalProp]): List[UniversalProp] =
    if(lines.isEmpty) res.reverse else {
      val Array(key,typeName) = lines.head.split(splitter)
      val ("0x", hex) = key.splitAt(2)
      val tag = Integer.parseInt(hex, 16)
      val value = lines.tail.takeWhile(_.head == splitter).map(_.tail)
      val prop =
        if(typeName.nonEmpty) propTypeRegistry(typeName).create(tag,value)
        else UniversalPropImpl(tag,UniversalNode(parseProps(splitter, value, Nil)))(UniversalProtoAdapter)
      parseProps(splitter, lines.drop(value.size), prop :: res)
    }
}

trait LinesToUniversalProp {
  def create(tag: Int, lines: List[String]): UniversalProp
}
/*
object LinesToUniversalProp {
  type Prop = (Int, List[String]) ⇒ UniversalProp
  def String: Prop = (tag,lines) ⇒ UniversalPropImpl(tag,lines.mkString("\n"))(ProtoAdapter.STRING)



  //case "Int" ⇒ "com.squareup.wire.ProtoAdapter.SINT32"
  //case "okio.ByteString" ⇒ "com.squareup.wire.ProtoAdapter.BYTES"
}
*/

