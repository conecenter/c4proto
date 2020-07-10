
package ee.cone.c4actor

import ee.cone.c4actor.ArgTypes._
import ee.cone.c4actor.Types.{ClName, FieldId, SrcId, TypeId}
import ee.cone.c4assemble.Single
import ee.cone.c4di.{c4, provide}
import ee.cone.c4proto._
import okio.ByteString

import scala.collection.immutable.Seq

@c4("ProtoApp") final class SimpleArgAdapterProvider {
  @provide def get[T](
    typeKey: StrictTypeKey[T],
    defaultArguments: List[DefaultArgument[T]],
    protoAdapters: List[ProtoAdapter[T]],
  ): Seq[ArgAdapter[T]] = protoAdapters.map { protoAdapter =>
    val defaultValue = Single.option(defaultArguments) match {
      case Some(defaultValue) => defaultValue
      case None => throw new Exception(s"Couldn't find DefaultArgument[$typeKey]")
    }
    new NoWrapArgAdapter[T](defaultValue.value, protoAdapter)
  }
  @provide def getList[T](protoAdapter: ProtoAdapter[T]): Seq[ArgAdapter[List[T]]] =
    Seq(new ListArgAdapter[T](()=>protoAdapter))
  @provide def getOption[T](protoAdapter: ProtoAdapter[T]): Seq[ArgAdapter[Option[T]]] =
    Seq(new OptionArgAdapter[T](()=>protoAdapter))
  @provide def getLazyList[T](protoAdapters: DeferredSeq[ProtoAdapter[T]]): Seq[ArgAdapter[LazyList[T]]] =
    Seq(new ListArgAdapter[T](()=>Single(protoAdapters.value)))
  @provide def getLazyOption[T](protoAdapters: DeferredSeq[ProtoAdapter[T]]): Seq[ArgAdapter[LazyOption[T]]] =
    Seq(new OptionArgAdapter[T](()=>Single(protoAdapters.value)))
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

class ListArgAdapter[Value](inner: ()=>ProtoAdapter[Value]) extends ArgAdapter[List[Value]] {
  def encodedSizeWithTag(tag: Int, value: List[Value]): Int =
    value.foldLeft(0)((res,item)=>res+inner().encodedSizeWithTag(tag,item))
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: List[Value]): Unit =
    value.foreach(item=>inner().encodeWithTag(writer,tag,item))
  def defaultValue: List[Value] = Nil
  def decodeReduce(reader: ProtoReader, prev: List[Value]): List[Value] =
    inner().decode(reader) :: prev
  def decodeFix(prev: List[Value]): List[Value] = prev.reverse
}

class OptionArgAdapter[Value](inner: ()=>ProtoAdapter[Value]) extends ArgAdapter[Option[Value]] {
  def encodedSizeWithTag(tag: Int, value: Option[Value]): Int =
    value.foldLeft(0)((res,item)=>res+inner().encodedSizeWithTag(tag,item))
  def encodeWithTag(writer: ProtoWriter, tag: Int, value: Option[Value]): Unit =
    value.foreach(item=>inner().encodeWithTag(writer,tag,item))
  def defaultValue: Option[Value] = None
  def decodeReduce(reader: ProtoReader, prev: Option[Value]): Option[Value] =
    Option(inner().decode(reader))
  def decodeFix(prev: Option[Value]): Option[Value] = prev
}

@c4("ProtoApp") final class IntDefaultArgument extends DefaultArgument[Int](0)
@c4("ProtoApp") final class LongDefaultArgument extends DefaultArgument[Long](0L)
@c4("ProtoApp") final class BooleanDefaultArgument extends DefaultArgument[Boolean](false)
@c4("ProtoApp") final class ByteStringDefaultArgument extends DefaultArgument[ByteString](ByteString.EMPTY)
@c4("ProtoApp") final class OKIOByteStringDefaultArgument extends DefaultArgument[okio.ByteString](ByteString.EMPTY)
@c4("ProtoApp") final class StringDefaultArgument extends DefaultArgument[String]("")
@c4("ProtoApp") final class SrcIdDefaultArgument extends DefaultArgument[SrcId]("")
@c4("ProtoApp") final class TypeIdDefaultArgument extends DefaultArgument[TypeId](0L)
@c4("ProtoApp") final class FieldIdDefaultArgument extends DefaultArgument[FieldId](0L)
@c4("ProtoApp") final class ClNameDefaultArgument extends DefaultArgument[ClName]("")

import com.squareup.wire.ProtoAdapter._

@c4("ProtoApp") final class PrimitiveProtoAdapterProvider {
  @provide def getBoolean: Seq[ProtoAdapter[Boolean]] = List(BOOL.asInstanceOf[ProtoAdapter[Boolean]])
  @provide def getInt: Seq[ProtoAdapter[Int]] = List(SINT32.asInstanceOf[ProtoAdapter[Int]])
  @provide def getLong: Seq[ProtoAdapter[Long]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
  @provide def getByteString: Seq[ProtoAdapter[ByteString]] = List(BYTES)
  @provide def getOKIOByteString: Seq[ProtoAdapter[okio.ByteString]] = List(BYTES)
  @provide def getString: Seq[ProtoAdapter[String]] = List(STRING)
  @provide def getSrcId: Seq[ProtoAdapter[SrcId]] = List(STRING)
  @provide def getTypeId: Seq[ProtoAdapter[TypeId]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
  @provide def getFieldId: Seq[ProtoAdapter[FieldId]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
}

/*
@c4("ProtoApp") class DefProtoAdapterFactory {
  def provideStringProtoAdapter(): ProtoAdapter[String] = STRING
  def provideBooleanProtoAdapter(): ProtoAdapter[Boolean] = BOOL
  //def provide...

}*/

@c4("ProtoApp") final class QAdapterRegistryProvider(adapters: List[HasId]) {
  @provide def get: Seq[QAdapterRegistry] = {
    val cAdapters = adapters.distinct.map(_.asInstanceOf[ProtoAdapter[Product] with HasId])
    Option(System.getenv("C4DEBUG_COMPONENTS")).foreach(_=>cAdapters.foreach(c=>println(s"adapter: ${c.className}")))
    val byName = CheckedMap(cAdapters.map(a => a.className -> a))
    val byId = CheckedMap(cAdapters.collect{ case a if a.hasId => a.id -> a })
    List(new QAdapterRegistryImpl(byName,byId))
  }
}

class QAdapterRegistryImpl(
  val byName: Map[String, ProtoAdapter[Product] with HasId],
  val byId: Map[Long, ProtoAdapter[Product] with HasId]
) extends QAdapterRegistry

@c4("ProtoApp") final class ProductProtoAdapter(
  qAdapterRegistryD: DeferredSeq[QAdapterRegistry]
) extends ProtoAdapter[Product](FieldEncoding.LENGTH_DELIMITED, classOf[Product]) {
  def redact(e: Product): Product = e
  private lazy val qAdapterRegistry = Single(qAdapterRegistryD.value)
  def encodedSize(value: Product): Int = {
    val adapter = qAdapterRegistry.byName(value.getClass.getName)
    adapter.encodedSizeWithTag(Math.toIntExact(adapter.id), value)
  }
  def encode(writer: ProtoWriter, value: Product): Unit = {
    val adapter = qAdapterRegistry.byName(value.getClass.getName)
    adapter.encodeWithTag(writer, Math.toIntExact(adapter.id), value)
  }
  def decode(reader: ProtoReader): Product = {
    val token = reader.beginMessage()
    val id = reader.nextTag()
    val adapter = qAdapterRegistry.byId(id)
    val res = adapter.decode(reader)
    assert(reader.nextTag() == -1)
    reader.endMessage(token)
    res
  }
}
