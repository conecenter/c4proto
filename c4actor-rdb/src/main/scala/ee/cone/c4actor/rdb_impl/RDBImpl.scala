package ee.cone.c4actor.rdb_impl

import com.squareup.wire.{FieldEncoding, ProtoAdapter, ProtoReader, ProtoWriter}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{CallableStatement, PreparedStatement}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

import FromExternalDBProtocol.DBOffset
import ToExternalDBProtocol.HasState
import ToExternalDBTypes.NeedSrcId
import ee.cone.c4actor.QProtocol.{Offset, Update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4proto
import ee.cone.c4proto._
import okio.ByteString

import scala.util.matching.Regex

object RDBTypes {
  private val msPerDay: Int = 24 * 60 * 60 * 1000
  private val epoch = "to_date('19700101','yyyymmdd')"
  //
  def encode(p: Object): String = p match {
    case v: String ⇒ v
    case v: java.lang.Boolean ⇒ if(v) "T" else ""
    case v: java.lang.Long ⇒ v.toString
    case v: BigDecimal ⇒ v.bigDecimal.toString
    case v: Instant ⇒ v.toEpochMilli.toString
  }
  def sysTypes: List[String] = List(
    classOf[String],
    classOf[java.lang.Boolean],
    classOf[java.lang.Long],
    classOf[BigDecimal],
    classOf[Instant]
  ).map(shortName)
  def shortName(cl: Class[_]): String = cl.getName.split("\\.").last
  def constructorToTypeArg(tp: String): String⇒String = tp match {
    case "Boolean" ⇒ a ⇒ s"case when $a then 'T' else null end"
    case at if at.endsWith("s") ⇒  a ⇒ s"case when $a is null then $at() else $a end"
    case _ ⇒ a ⇒ a
  }
  def encodeExpression(tp: String): String⇒String = tp match {
    case "Instant" ⇒ a ⇒ s"round(($a - $epoch) * $msPerDay)"
    case "Boolean" ⇒ a ⇒ s"(case when $a then 'T' else '' end)"
    case "String"|"Long"|"BigDecimal" ⇒ a ⇒ a
  }
  def toUniversalProp(tag: Int, typeName: String, value: String): UniversalProp = typeName match {
    case "String" ⇒
      UniversalPropImpl[String](tag,value)(ProtoAdapter.STRING)
    case "Boolean" ⇒
      UniversalPropImpl[java.lang.Boolean](tag,value.nonEmpty)(ProtoAdapter.BOOL)
    case "Long" | "Instant" ⇒
      UniversalPropImpl[java.lang.Long](tag,java.lang.Long.parseLong(value))(ProtoAdapter.SINT64)
    case "BigDecimal" ⇒
      val BigDecimalFactory(scale,bytes) = BigDecimal(value)
      val scaleProp = UniversalPropImpl(0x0001,scale:Integer)(ProtoAdapter.SINT32)
      val bytesProp = UniversalPropImpl(0x0002,bytes)(ProtoAdapter.BYTES)
      UniversalPropImpl(tag,UniversalNode(List(scaleProp,bytesProp)))(UniversalProtoAdapter)
  }
}

////

class ProtocolDBOption(val protocol: Protocol) extends ExternalDBOption
class NeedDBOption(val need: Need) extends ExternalDBOption
class ToDBOption(val className: String, val code: String, val assemble: Assemble) extends ExternalDBOption
class FromDBOption(val className: String) extends ExternalDBOption
class UserDBOption(val user: String) extends ExternalDBOption

class ExternalDBOptionFactoryImpl(qMessages: QMessages, util: DDLUtil) extends ExternalDBOptionFactory {
  def dbProtocol(value: Protocol): ExternalDBOption = new ProtocolDBOption(value)
  def fromDB[P <: Product](cl: Class[P]): ExternalDBOption = new FromDBOption(cl.getName)
  def toDB[P <: Product](cl: Class[P], code: String): ExternalDBOption =
    new ToDBOption(cl.getName, code, new ToExternalDBItemAssemble(qMessages,cl))
  def createOrReplace(key: String, args: String, code: String): ExternalDBOption =
    new NeedDBOption(util.createOrReplace(key,args,code))
  def grantExecute(key: String): ExternalDBOption = new NeedDBOption(GrantExecute(key))
  def dbUser(user: String): ExternalDBOption = new UserDBOption(user)
}

////

@protocol object ToExternalDBProtocol extends c4proto.Protocol {
  @Id(0x0063) case class HasState(
    @Id(0x0061) srcId: String,
    @Id(0x0064) valueTypeId: Long,
    @Id(0x0065) value: okio.ByteString
  )
}

object ToBytes {
  def apply(value: Long): Array[Byte] =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
}

object ToExternalDBTypes {
  type NeedSrcId = SrcId
}

object ToExternalDBAssembles {
  def apply(options: List[ExternalDBOption]): List[Assemble] =
    new ToExternalDBTxAssemble ::
      options.collect{ case o: ToDBOption ⇒ o.assemble }
}

@assemble class ToExternalDBItemAssemble[Item<:Product](
  messages: QMessages,
  classItem: Class[Item]
)  extends Assemble {
  def join(
    key: SrcId,
    items: Values[Item]
  ): Values[(NeedSrcId,HasState)] =
    for(item ← items; e ← LEvent.update(item)) yield {
      val u = messages.toUpdate(e)
      val key = UUID.nameUUIDFromBytes(ToBytes(u.valueTypeId) ++ u.srcId.getBytes(UTF_8)).toString
      key → HasState(key,u.valueTypeId,u.value)
    }
}

@assemble class ToExternalDBTxAssemble extends Assemble {
  def join(
    key: SrcId,
    @by[NeedSrcId] needStates: Values[HasState],
    hasStates: Values[HasState]
  ): Values[(SrcId, TxTransform)] = {
    if (hasStates == needStates) Nil else
      List(key → ToExternalDBTx(key, Single.option(hasStates), Single.option(needStates)))
  }
}

case object RDBSleepUntilKey extends WorldKey[(Instant,Option[HasState])]((Instant.MIN,None))

case class ToExternalDBTx(txSrcId: SrcId, from: Option[HasState], to: Option[HasState]) extends TxTransform {
  def transform(local: World): World = {
    val now = Instant.now()
    val (until,wasTo) = RDBSleepUntilKey.of(local)
    if(to == wasTo && now.isBefore(until)) local
    else WithJDBCKey.of(local){ conn ⇒
      val registry = QAdapterRegistryKey.of(local)()
      val protoToString = new ProtoToString(registry)
      def recode(stateOpt: Option[HasState]): String = stateOpt.map{ state ⇒
        protoToString.recode(state.valueTypeId, state.value)
      }.getOrElse("")
      val (valueTypeId,srcId) =
        Single(List(from,to).flatten.map(s⇒(s.valueTypeId,s.srcId)).distinct)
      val List(delay:java.lang.Long) = conn.procedure("upd")
        .in(Thread.currentThread.getName).in(HexStr(valueTypeId)).in(srcId)
        .in(recode(from)).in(recode(to)).outLong.call()
      if(delay > 0)
        RDBSleepUntilKey.set((now.plusMillis(delay),to))(local)
      else LEvent.add(
        from.toList.flatMap(LEvent.delete) ++ to.toList.flatMap(LEvent.update)
      )(local)
    }
  }
}

////

@protocol object FromExternalDBProtocol extends c4proto.Protocol {
  @Id(0x0060) case class DBOffset(
    @Id(0x0061) srcId: String,
    @Id(0x0062) value: Long
  )
}

@assemble class FromExternalDBSyncAssemble extends Assemble {
  def joinTxTransform(
    key: SrcId,
    offset: Values[Offset]
  ): Values[(SrcId,TxTransform)] =
    List("externalDBSync").map(k⇒k→FromExternalDBSyncTransform(k))
}

case class FromExternalDBSyncTransform(srcId:SrcId) extends TxTransform {
  def transform(local: World): World = WithJDBCKey.of(local){ conn ⇒
    val world = TxKey.of(local).world
    val offset =
      Single.option(By.srcId(classOf[DBOffset]).of(world).getOrElse(srcId,Nil))
        .getOrElse(DBOffset(srcId, 0L))
    //println("offset",offset)//, By.srcId(classOf[Invoice]).of(world).size)
    val List(nextOffsetValue: java.lang.Long, textEncoded: String) =
      conn.procedure("poll").in(offset.value).outLong.outText.call()
    val updateOffset = List(DBOffset(srcId, nextOffsetValue)).filter(offset!=_)
      .map(n⇒LEvent.add(LEvent.update(n)))
    val updateWorld = if(textEncoded.isEmpty) Nil else
      List(TxKey.modify(_.add((new IndentedParser).toUpdates(textEncoded))))
    Function.chain(updateOffset ::: updateWorld)(local)
  }
}

////


////

case class UniversalNode(props: List[UniversalProp])

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

object UniversalProtoAdapter extends ProtoAdapter[UniversalNode](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalNode]) {
  def encodedSize(value: UniversalNode): Int =
    value.props.map(_.encodedSize).sum
  def encode(writer: ProtoWriter, value: UniversalNode): Unit =
    value.props.foreach(_.encode(writer))
  def decode(reader: ProtoReader): UniversalNode = throw new Exception("not implemented")
}

class IndentedParser(
  splitter: Char = ' ', lineSplitter: String = "\n"
) {
  //@tailrec final
  private def parseProp(key: String, value: List[String]): UniversalProp = {
    val Array(xHex,handlerName) = key.split(splitter)
    val ("0x", hex) = xHex.splitAt(2)
    val tag = Integer.parseInt(hex, 16)
    if(handlerName != "Node")
      RDBTypes.toUniversalProp(tag,handlerName,value.mkString(lineSplitter))
    else UniversalPropImpl(tag,UniversalNode(parseProps(value, Nil)))(UniversalProtoAdapter)
  }
  private def parseProps(lines: List[String], res: List[UniversalProp]): List[UniversalProp] =
    if(lines.isEmpty) res.reverse else {
      val key = lines.head
      val value = lines.tail.takeWhile(_.head == splitter).map(_.tail)
      val left = lines.tail.drop(value.size)
      parseProps(left, parseProp(key, value) :: res)
    }

  def toUpdates(textEncoded: String): List[Update] = {
    val lines = textEncoded.split(lineSplitter).filter(_.nonEmpty).toList
    val universalNode = UniversalNode(parseProps(lines, Nil))
    //println(PrettyProduct.encode(universalNode))
    universalNode.props.map{ prop ⇒
      val srcId = prop.value match {
        case node: UniversalNode ⇒ node.props.head.value match {
          case s: String ⇒ s
        }
      }
      Update(srcId, prop.tag, ToByteString(prop.encodedValue))
    }
  }
}

class ProtoToString(registry: QAdapterRegistry){
  private def esc(id: Long, handler: String, value: String): String =
    s"\n${HexStr(id)} $handler${value.replace("\n","\n ")}"
  private def encode(id: Long, p: Any): String = p match {
    case Nil | None ⇒ ""
    case Some(e) ⇒ encode(id, e)
    case l: List[_] ⇒ l.map(encode(id, _)).mkString
    case p: Product ⇒
      val adapter = registry.byName(p.getClass.getName)
      esc(id, "Node", adapter.props.zipWithIndex.map{
        case(prop,i) ⇒ encode(prop.id, p.productElement(i))
      }.mkString)
    case e: Object ⇒
      esc(id, RDBTypes.shortName(e.getClass), s"\n${RDBTypes.encode(e)}")
  }
  def recode(valueTypeId: Long, value: ByteString): String =
    registry.byId.get(valueTypeId).map{ adapter ⇒
      encode(adapter.id, adapter.decode(value.toByteArray))
    }.getOrElse("")
}

//protobuf universal draft