
package ee.cone.c4actor

import com.squareup.wire.{ProtoAdapter, ProtoReader, ProtoWriter}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ArgTypes._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{ArgAdapter, DataCategory, HasId, MetaProp, N_Cat, TypeKey, c4, provide}
import okio.ByteString

import scala.collection.immutable.Seq

@c4("ProtoApp") class ArgAdapterComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[ArgAdapter[_]] with LazyLogging {
  import componentRegistry._
  def getProtoAdapters(args: Seq[TypeKey]): Seq[ProtoAdapter[Any]] =
    componentRegistry.resolve(classOf[ProtoAdapter[Any]],args).value

  def forTypes(args: Seq[TypeKey]): Seq[ArgAdapter[_]] = {
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

@c4("ProtoApp") class LazyListArgAdapterFactory extends LazyArgAdapterFactory[LazyList[_]](new ListArgAdapter(_))
@c4("ProtoApp") class ListArgAdapterFactory extends ArgAdapterFactory[List[_]](new ListArgAdapter(_))
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

@c4("ProtoApp") class LazyOptionArgAdapterFactory extends LazyArgAdapterFactory[LazyOption[_]](new OptionArgAdapter(_))
@c4("ProtoApp") class OptionArgAdapterFactory extends ArgAdapterFactory[Option[_]](new OptionArgAdapter(_))
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

@c4("ProtoApp") class IntDefaultArgument extends DefaultArgument[Int](0)
@c4("ProtoApp") class LongDefaultArgument extends DefaultArgument[Long](0L)
@c4("ProtoApp") class BooleanDefaultArgument extends DefaultArgument[Boolean](false)
@c4("ProtoApp") class ByteStringDefaultArgument extends DefaultArgument[ByteString](ByteString.EMPTY)
@c4("ProtoApp") class OKIOByteStringDefaultArgument extends DefaultArgument[okio.ByteString](ByteString.EMPTY)
@c4("ProtoApp") class StringDefaultArgument extends DefaultArgument[String]("")
@c4("ProtoApp") class SrcIdDefaultArgument extends DefaultArgument[SrcId]("")

import com.squareup.wire.ProtoAdapter._

@c4("ProtoApp") class PrimitiveProtoAdapterProvider {
  @provide def getBoolean: Seq[ProtoAdapter[Boolean]] = List(BOOL.asInstanceOf[ProtoAdapter[Boolean]])
  @provide def getInt: Seq[ProtoAdapter[Int]] = List(SINT32.asInstanceOf[ProtoAdapter[Int]])
  @provide def getLong: Seq[ProtoAdapter[Long]] = List(SINT64.asInstanceOf[ProtoAdapter[Long]])
  @provide def getByteString: Seq[ProtoAdapter[ByteString]] = List(BYTES)
  @provide def getOKIOByteString: Seq[ProtoAdapter[okio.ByteString]] = List(BYTES)
  @provide def getString: Seq[ProtoAdapter[String]] = List(STRING)
  @provide def getSrcId: Seq[ProtoAdapter[SrcId]] = List(STRING)
}

/*
@c4("ProtoApp") class DefProtoAdapterFactory {
  def provideStringProtoAdapter(): ProtoAdapter[String] = STRING
  def provideBooleanProtoAdapter(): ProtoAdapter[Boolean] = BOOL
  //def provide...

}*/

@c4("ProtoApp") class QAdapterRegistryProvider(adapters: List[HasId]) {
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

@c4("RichDataCompApp") class LocalQAdapterRegistryInit(qAdapterRegistry: QAdapterRegistry) extends ToInject {
  def toInject: List[Injectable] = QAdapterRegistryKey.set(qAdapterRegistry)
}

@c4("ProtoApp") class ProductProtoAdapter(
  qAdapterRegistryD: DeferredSeq[QAdapterRegistry]
) extends ProtoAdapter[Product](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[Product]) with HasId {
  def id: Long = throw new Exception
  def hasId: Boolean = false
  def className: String = classOf[Product].getName
  def props: List[MetaProp] = Nil
  //
  def categories: List[DataCategory] = List(N_Cat)
  def cl: Class[_] = classOf[Product]
  def shortName: Option[String] = None
  //
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
