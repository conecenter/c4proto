
package ee.cone.c4actor

import com.squareup.wire.{ProtoAdapter, ProtoReader, ProtoWriter}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ArgTypes._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{ArgAdapter, HasId, TypeKey, c4component}
import okio.ByteString

import scala.collection.immutable.Seq

@c4component("ProtoAutoApp") class ArgAdapterComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[ArgAdapter[_]] with LazyLogging {
  import componentRegistry._
  def getProtoAdapters(args: Seq[TypeKey]): Seq[ProtoAdapter[Any]] =
    componentRegistry.resolve(classOf[ProtoAdapter[Any]],args).value ++
    componentRegistry.resolve(classOf[ProtoAdapterHolder[Any]],args).value.map(_.value)

  def forTypes(args: Seq[TypeKey]): Seq[ArgAdapter[_]] = {
    val simpleRes = getProtoAdapters(args).map{ protoAdapter =>
      val defaultValue = Single(componentRegistry.resolve(classOf[DefaultArgument[_]],args).value)
      new NoWrapArgAdapter[Any](defaultValue.value,protoAdapter)
    }
    val arg = Single(args)
    val collectionType = arg.copy(args=Nil)
    val itemTypes = arg.args
    def getProtoAdapter = Single(getProtoAdapters(itemTypes))

    val strictRes = resolve(classOf[ArgAdapterFactory[_]],List(collectionType)).value
      .map{ f => val a = getProtoAdapter; f.wrap(()=>a) }
    val lazyRes = resolve(classOf[LazyArgAdapterFactory[_]],List(collectionType)).value
      .map{ f => lazy val a = getProtoAdapter; f.wrap(()=>a) }
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

@c4component("ProtoAutoApp") class LazyListArgAdapterFactory extends LazyArgAdapterFactory[LazyList[_]](new ListArgAdapter(_))
@c4component("ProtoAutoApp") class ListArgAdapterFactory extends ArgAdapterFactory[List[_]](new ListArgAdapter(_))
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

@c4component("ProtoAutoApp") class LazyOptionArgAdapterFactory extends LazyArgAdapterFactory[LazyOption[_]](new OptionArgAdapter(_))
@c4component("ProtoAutoApp") class OptionArgAdapterFactory extends ArgAdapterFactory[Option[_]](new OptionArgAdapter(_))
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

@c4component("ProtoAutoApp") class IntDefaultArgument extends DefaultArgument[Int](0)
@c4component("ProtoAutoApp") class LongDefaultArgument extends DefaultArgument[Long](0L)
@c4component("ProtoAutoApp") class BooleanDefaultArgument extends DefaultArgument[Boolean](false)
@c4component("ProtoAutoApp") class ByteStringDefaultArgument extends DefaultArgument[ByteString](ByteString.EMPTY)
@c4component("ProtoAutoApp") class OKIOByteStringDefaultArgument extends DefaultArgument[okio.ByteString](ByteString.EMPTY)
@c4component("ProtoAutoApp") class StringDefaultArgument extends DefaultArgument[String]("")
@c4component("ProtoAutoApp") class SrcIdDefaultArgument extends DefaultArgument[SrcId]("")

import com.squareup.wire.ProtoAdapter._

@c4component("ProtoAutoApp") class BooleanProtoAdapterHolder extends ProtoAdapterHolder[Boolean](BOOL.asInstanceOf[ProtoAdapter[Boolean]])
@c4component("ProtoAutoApp") class IntProtoAdapterHolder extends ProtoAdapterHolder[Int](SINT32.asInstanceOf[ProtoAdapter[Int]])
@c4component("ProtoAutoApp") class LongProtoAdapterHolder extends ProtoAdapterHolder[Long](SINT64.asInstanceOf[ProtoAdapter[Long]])
@c4component("ProtoAutoApp") class ByteStringProtoAdapterHolder extends ProtoAdapterHolder[ByteString](BYTES)
@c4component("ProtoAutoApp") class OKIOByteStringProtoAdapterHolder extends ProtoAdapterHolder[okio.ByteString](BYTES)
@c4component("ProtoAutoApp") class StringProtoAdapterHolder extends ProtoAdapterHolder[String](STRING)
@c4component("ProtoAutoApp") class SrcIdProtoAdapterHolder extends ProtoAdapterHolder[SrcId](STRING)

@c4component("ProtoAutoApp") class QAdapterRegistryImpl(adapters: List[ProtoAdapter[_]])(
  val byName: Map[String, ProtoAdapter[Product] with HasId] =
    CheckedMap(adapters.collect{ case a: HasId => a.className -> a.asInstanceOf[ProtoAdapter[Product] with HasId] }),
  val byId: Map[Long, ProtoAdapter[Product] with HasId] =
    CheckedMap(adapters.collect{ case a: HasId if a.hasId => a.id -> a.asInstanceOf[ProtoAdapter[Product] with HasId] })
) extends QAdapterRegistry

@c4component("RichDataAutoApp") class LocalQAdapterRegistryInit(qAdapterRegistry: QAdapterRegistry) extends ToInject {
  def toInject: List[Injectable] = QAdapterRegistryKey.set(qAdapterRegistry)
}

@c4component("ProtoAutoApp") class ProductProtoAdapterHolder(
  qAdapterRegistry: QAdapterRegistry
) extends ProtoAdapterHolder[Product](new ProductProtoAdapter(qAdapterRegistry))
class ProductProtoAdapter(
  qAdapterRegistry: QAdapterRegistry
) extends ProtoAdapter[Product](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[Product]) {
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
