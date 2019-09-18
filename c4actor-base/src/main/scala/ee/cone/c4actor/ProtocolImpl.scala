
package ee.cone.c4actor

import com.squareup.wire.{ProtoAdapter, ProtoReader, ProtoWriter}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ArgTypes._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{ArgAdapter, TypeKey, c4component}
import okio.ByteString

import scala.collection.immutable.Seq

@c4component class ArgAdapterComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[ArgAdapter[_]] with LazyLogging {
  import componentRegistry._
  def getProtoAdapters(args: Seq[TypeKey]): Seq[ProtoAdapter[Any]] =
    componentRegistry.resolve(classOf[ProtoAdapter[Any]],args) ++
    componentRegistry.resolve(classOf[ProtoAdapterHolder[Any]],args).map(_.value)

  def forTypes(args: Seq[TypeKey]): Seq[ArgAdapter[_]] = {
    val simpleRes = getProtoAdapters(args).map{ protoAdapter ⇒
      val defaultValue = Single(componentRegistry.resolve(classOf[DefaultArgument[_]],args))
      new NoWrapArgAdapter[Any](defaultValue.value,protoAdapter)
    }
    val arg = Single(args)
    val collectionType = arg.copy(args=Nil)
    val itemTypes = arg.args
    def getProtoAdapter = Single(getProtoAdapters(itemTypes))

    val strictRes = resolve(classOf[ArgAdapterFactory[_]],List(collectionType))
      .map{ f ⇒ val a = getProtoAdapter; f.wrap(()⇒a) }
    val lazyRes = resolve(classOf[LazyArgAdapterFactory[_]],List(collectionType))
      .map{ f ⇒ lazy val a = getProtoAdapter; f.wrap(()⇒a) }
    logger.trace(s"collectionType: $collectionType, res: ${simpleRes.size} ${strictRes.size} ${lazyRes.size}")
    simpleRes ++ strictRes ++ lazyRes
  }
}

class NoWrapArgAdapter[Value](val defaultValue: Value, inner: ProtoAdapter[Value]) extends ArgAdapter[Value] {
  private def nonEmpty(value: Value): Boolean = value != defaultValue
  def encodedSizeWithTag(tag: Int, value: Value): Int =
    if(nonEmpty(value)) inner.encodedSizeWithTag(tag,value) else 0
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: Value): Unit =
    if(nonEmpty(value)) inner.encodeWithTag(writer,tag,value)
  def decodeReduce(reader: ProtoReader, prev: Value): Value = inner.decode(reader)
  def decodeFix(prev: Value): Value = prev
}

@c4component class LazyListArgAdapterFactory extends LazyArgAdapterFactory[LazyList[_]](new ListArgAdapter(_))
@c4component class ListArgAdapterFactory extends ArgAdapterFactory[List[_]](new ListArgAdapter(_))
class ListArgAdapter[Value](inner: ()⇒ProtoAdapter[Value]) extends ArgAdapter[List[Value]] {
  def encodedSizeWithTag(tag: Int, value: List[Value]): Int =
    value.foldLeft(0)((res,item)⇒res+inner().encodedSizeWithTag(tag,item))
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: List[Value]): Unit =
    value.foreach(item⇒inner().encodeWithTag(writer,tag,item))
  def defaultValue: List[Value] = Nil
  def decodeReduce(reader: ProtoReader, prev: List[Value]): List[Value] =
    inner().decode(reader) :: prev
  def decodeFix(prev: List[Value]): List[Value] = prev.reverse
}

@c4component class LazyOptionArgAdapterFactory extends LazyArgAdapterFactory[LazyOption[_]](new OptionArgAdapter(_))
@c4component class OptionArgAdapterFactory extends ArgAdapterFactory[Option[_]](new OptionArgAdapter(_))
class OptionArgAdapter[Value](inner: ()⇒ProtoAdapter[Value]) extends ArgAdapter[Option[Value]] {
  def encodedSizeWithTag(tag: Int, value: Option[Value]): Int =
    value.foldLeft(0)((res,item)⇒res+inner().encodedSizeWithTag(tag,item))
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: Option[Value]): Unit =
    value.foreach(item⇒inner().encodeWithTag(writer,tag,item))
  def defaultValue: Option[Value] = None
  def decodeReduce(reader: ProtoReader, prev: Option[Value]): Option[Value] =
    Option(inner().decode(reader))
  def decodeFix(prev: Option[Value]): Option[Value] = prev
}

@c4component class IntDefaultArgument extends DefaultArgument[Int](0)
@c4component class LongDefaultArgument extends DefaultArgument[Long](0L)
@c4component class BooleanDefaultArgument extends DefaultArgument[Boolean](false)
@c4component class ByteStringDefaultArgument extends DefaultArgument[ByteString](ByteString.EMPTY)
@c4component class OKIOByteStringDefaultArgument extends DefaultArgument[okio.ByteString](ByteString.EMPTY)
@c4component class StringDefaultArgument extends DefaultArgument[String]("")
@c4component class SrcIdDefaultArgument extends DefaultArgument[SrcId]("")

import com.squareup.wire.ProtoAdapter._

@c4component class BooleanProtoAdapterHolder extends ProtoAdapterHolder[Boolean](BOOL.asInstanceOf[ProtoAdapter[Boolean]])
@c4component class IntProtoAdapterHolder extends ProtoAdapterHolder[Int](SINT32.asInstanceOf[ProtoAdapter[Int]])
@c4component class LongProtoAdapterHolder extends ProtoAdapterHolder[Long](SINT64.asInstanceOf[ProtoAdapter[Long]])
@c4component class ByteStringProtoAdapterHolder extends ProtoAdapterHolder[ByteString](BYTES)
@c4component class OKIOByteStringProtoAdapterHolder extends ProtoAdapterHolder[okio.ByteString](BYTES)
@c4component class StringProtoAdapterHolder extends ProtoAdapterHolder[String](STRING)
@c4component class SrcIdProtoAdapterHolder extends ProtoAdapterHolder[SrcId](STRING)


/*
@c4component class BooleanProtoAdapter extends ProtoAdapter[Boolean](FieldEncoding.VARINT, classOf[Boolean]) {
  def encodedSize(value: Boolean): Int = BOOL.encodedSize(value)
  def encode(writer: ProtoWriter, value: Boolean): Unit = BOOL.encode(writer,value)
  def decode(reader: ProtoReader): Boolean = BOOL.decode(reader)
}
@c4component class IntProtoAdapter extends ProtoAdapter[Int](FieldEncoding.VARINT, classOf[Int]) {
  def encodedSize(value: Int): Int = SINT32.encodedSize(value)
  def encode(writer: ProtoWriter, value: Int): Unit = SINT32.encode(writer,value)
  def decode(reader: ProtoReader): Int = SINT32.decode(reader)
}
@c4component class LongProtoAdapter extends ProtoAdapter[Long](FieldEncoding.VARINT, classOf[Long]) {
  def encodedSize(value: Long): Int = SINT64.encodedSize(value)
  def encode(writer: ProtoWriter, value: Long): Unit = SINT64.encode(writer,value)
  def decode(reader: ProtoReader): Long = SINT64.decode(reader)
}

trait ProtoAdapterMethods[T] {
  def inner: ProtoAdapter[T]
  def encodedSize(value: T): Int = inner.encodedSize(value)
  def encode(writer: ProtoWriter, value: T): Unit = inner.encode(writer,value)
  def decode(reader: ProtoReader): T = inner.decode(reader)
}

@c4component class ByteStringProtoAdapter extends ProtoAdapter[ByteString](FieldEncoding.LENGTH_DELIMITED, classOf[ByteString]) with ProtoAdapterMethods[ByteString] {
  def inner = BYTES
}
@c4component class StringProtoAdapter extends ProtoAdapter[String](FieldEncoding.LENGTH_DELIMITED, classOf[String]) {
  def encodedSize(value: String): Int = STRING.encodedSize(value)
  def encode(writer: ProtoWriter, value: String): Unit = STRING.encode(writer,value)
  def decode(reader: ProtoReader): String = STRING.decode(reader)
}
*/