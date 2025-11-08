
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ArgTypes._
import ee.cone.c4actor.Types.{ClName, FieldId, SrcId, TypeId}
import ee.cone.c4assemble.{Interner, Single}
import ee.cone.c4di.Types.ComponentFactory
import ee.cone.c4di.{CreateTypeKey, TypeKey, c4, provide}
import ee.cone.c4proto._
import okio.ByteString

import scala.collection.immutable.Seq

@c4("ProtoApp") final class ArgAdapterComponentFactoryProvider(
  componentRegistry: ComponentRegistry,
  argAdapterFactoryList: List[ArgAdapterFactory],
  lazyArgAdapterFactoryList: List[LazyArgAdapterFactory],
)(
  argAdapterFactoryMap: Map[TypeKey,ArgAdapterFactory] = CheckedMap(argAdapterFactoryList.map(a=>a.key->a)),
  lazyArgAdapterFactoryMap: Map[TypeKey,LazyArgAdapterFactory] = CheckedMap(lazyArgAdapterFactoryList.map(a=>a.key->a)),
) extends LazyLogging {
  def getProtoAdapters(args: Seq[TypeKey]): Seq[ProtoAdapter[Any]] =
    componentRegistry.resolve(classOf[ProtoAdapter[Any]],args).value

  @provide def get: Seq[ComponentFactory[ArgAdapter[_]]] = List(args=>{
    val simpleRes = getProtoAdapters(args).map{ protoAdapter =>
      val defaultValue = Single.option(componentRegistry.resolve(classOf[DefaultArgument[_]], args).value) match {
        case Some(defaultValue) => defaultValue
        case None => throw new Exception(s"Couldn't find DefaultArgument[$args]")
      }
      new NoWrapArgAdapter[Any](defaultValue.value,protoAdapter)
    }
    val arg = Single(args)
    val collectionType = arg.copy(args=Nil)
    val itemTypes = arg.args
    def getProtoAdapter = getProtoAdapters(itemTypes) match {
      case Seq(a) => a
      case o => throw new Exception(s"non-single (${o.size}) of ${itemTypes}")
    }
    val strictRes = argAdapterFactoryMap.get(collectionType).toList
      .map{ f => val a = getProtoAdapter; f.wrap(()=>a) }
    val lazyRes = lazyArgAdapterFactoryMap.get(collectionType).toList
      .map{ f => lazy val a = getProtoAdapter; f.wrap(()=>a) }
    logger.trace(s"collectionType: $collectionType, res: ${simpleRes.size} ${strictRes.size} ${lazyRes.size}")
    simpleRes ++ strictRes ++ lazyRes
  })
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

@c4("ProtoApp") final class LazyListArgAdapterFactory(uKey: StrictTypeKey[LazyList[Unit]]) extends LazyArgAdapterFactory(uKey.value.copy(args=Nil), new ListArgAdapter(_))
@c4("ProtoApp") final class ListArgAdapterFactory(uKey: StrictTypeKey[List[Unit]]) extends ArgAdapterFactory(uKey.value.copy(args=Nil), new ListArgAdapter(_))
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

@c4("ProtoApp") final class LazyOptionArgAdapterFactory(uKey: StrictTypeKey[LazyOption[Unit]]) extends LazyArgAdapterFactory(uKey.value.copy(args=Nil), new OptionArgAdapter(_))
@c4("ProtoApp") final class OptionArgAdapterFactory(uKey: StrictTypeKey[Option[Unit]]) extends ArgAdapterFactory(uKey.value.copy(args=Nil), new OptionArgAdapter(_))
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

@c4("ProtoApp") final class PrimitiveProtoAdapterProvider(
  stringProtoAdapter: ProtoAdapter[String]
){
  @provide def getBoolean: Seq[ProtoAdapter[Boolean]] = List(BOOL.asInstanceOf[ProtoAdapter[Boolean]])
  @provide def getInt: Seq[ProtoAdapter[Int]] = List(SINT32.asInstanceOf[ProtoAdapter[Int]])
  @provide def getLong: Seq[ProtoAdapter[Long]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
  @provide def getByteString: Seq[ProtoAdapter[ByteString]] = List(BYTES)
  @provide def getOKIOByteString: Seq[ProtoAdapter[okio.ByteString]] = List(BYTES)
  //@provide def getString: Seq[ProtoAdapter[String]] = List(STRING)
  //@provide def getSrcId: Seq[ProtoAdapter[SrcId]] = List(STRING)
  @provide def getSrcId: Seq[ProtoAdapter[SrcId]] = List(stringProtoAdapter)
  @provide def getTypeId: Seq[ProtoAdapter[TypeId]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
  @provide def getFieldId: Seq[ProtoAdapter[FieldId]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
}

@c4("ProtoApp") final class StringProtoAdapter(
) extends ProtoAdapter[String](FieldEncoding.LENGTH_DELIMITED, classOf[Product]) {
  def decode(protoReader: ProtoReader): String = Interner.intern(STRING.decode(protoReader))
  def encode(protoWriter: ProtoWriter, e: String): Unit = STRING.encode(protoWriter,e)
  def encodedSize(e: String): Int = STRING.encodedSize(e)
  def redact(e: String): String = e
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
    if(ComponentRegistry.debug) cAdapters.foreach(c=>println(s"adapter: ${c.className}"))
    val byName = CheckedMap(cAdapters.map(a => a.className -> a))
    val byId = CheckedMap(cAdapters.collect{ case a if a.hasId => a.id -> a })
    List(QAdapterRegistryImpl()(byName,byId))
  }
}

case class QAdapterRegistryImpl()(
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
