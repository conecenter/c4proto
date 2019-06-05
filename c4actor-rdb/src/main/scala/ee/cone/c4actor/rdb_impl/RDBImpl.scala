package ee.cone.c4actor.rdb_impl

import com.squareup.wire.{FieldEncoding, ProtoAdapter, ProtoReader, ProtoWriter}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

import FromExternalDBProtocol.DBOffset
import ToExternalDBProtocol.HasState
import ToExternalDBTypes.{NeedSrcId, PseudoOrigNeedSrcId}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, Update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.rdb_impl.ToExternalDBAssembleTypes.PseudoOrig
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto._
import okio.ByteString



class RDBOptionFactoryImpl(toUpdate: ToUpdate) extends RDBOptionFactory {
  def dbProtocol(value: Protocol): ExternalDBOption = new ProtocolDBOption(value)
  def fromDB[P <: Product](cl: Class[P]): ExternalDBOption = new FromDBOption(cl.getName)
  def toDB[P <: Product](cl: Class[P], code: List[String]): ExternalDBOption =
    new ToDBOption(cl.getName, code, new ToExternalDBOrigAssemble(toUpdate,cl))
}

////

@protocol(ExchangeCat) object ToExternalDBProtocolBase   {
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
  type PseudoOrigNeedSrcId = SrcId
}

object ToExternalDBAssembles {
  def apply(options: List[ExternalDBOption]): List[Assemble] =
    new ToExternalDBTxAssemble ::
      options.collect{ case o: ToDBOption ⇒ o.assemble }
}

object ToExternalDBAssembleTypes {
  type PseudoOrig = SrcId
}

trait  ToExternalDBItemAssembleUtil {
  def toUpdate: ToUpdate

  def itemToHasState[Item <: Product]: Item ⇒ Values[(String,HasState)] = item ⇒
    for(e ← LEvent.update(item)) yield {
      val u = toUpdate.toUpdate(e)
      val key = UUID.nameUUIDFromBytes(ToBytes(u.valueTypeId) ++ u.srcId.getBytes(UTF_8)).toString
      key → HasState(key,u.valueTypeId,u.value)
    }
}

@assemble class ToExternalDBOrigAssembleBase[Item<:Product](
  val toUpdate: ToUpdate,
  classItem: Class[Item]
)  extends   ToExternalDBItemAssembleUtil {
  def OrigJoin(
    key: SrcId,
    item: Each[Item]
  ): Values[(NeedSrcId,HasState)] =
    itemToHasState(item)

  def PseudoOrigJoin(
    key: SrcId,
    @by[PseudoOrig] item: Each[Item]
  ): Values[(PseudoOrigNeedSrcId,HasState)] =
    itemToHasState(item)
}

@assemble class ToExternalDBTxAssembleBase extends   LazyLogging{
  type TypeHex = String
  def joinTasks(
    key: SrcId,
    @by[PseudoOrigNeedSrcId] pseudoNeedStates: Values[HasState],
    @by[NeedSrcId] needStates: Values[HasState],
    hasStates: Values[HasState]
  ): Values[(TypeHex, ToExternalDBTask)] = {
    val mergedNeedStates =
      if (needStates.isEmpty)
        pseudoNeedStates.toList
      else {
        if (pseudoNeedStates.nonEmpty) logger.warn(s"Orig and PseudoOrig conflict: O-$needStates,PSO-$pseudoNeedStates")
        needStates.toList
      }
    if (hasStates.toList == mergedNeedStates) Nil else {
      val typeHex = Hex(Single((hasStates ++ mergedNeedStates).map(_.valueTypeId).distinct))
      List(typeHex → ToExternalDBTask(key, typeHex, Single.option(hasStates), Single.option(mergedNeedStates)))
    }
  }
  def join(
    key: SrcId,
    @by[TypeHex] tasks: Values[ToExternalDBTask]
  ): Values[(SrcId,TxTransform)] = List(WithPK(ToExternalDBTx(key, tasks.toList)))
}

case class ToExternalDBTask(
  srcId: SrcId,
  typeHex: String,
  from: Option[HasState],
  to: Option[HasState]
)

case object RDBSleepUntilKey extends TransientLens[Map[SrcId,(Instant,Option[HasState])]](Map.empty)

case class ToExternalDBTx(typeHex: SrcId, tasks: List[ToExternalDBTask]) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val now = Instant.now()
    tasks.find{ task ⇒
      val skip = RDBSleepUntilKey.of(local)
      val(until,wasTo) = skip.getOrElse(task.srcId, (Instant.MIN,None))
      until.isBefore(now) || task.to != wasTo
    }.map{ task ⇒ WithJDBCKey.of(local) { conn ⇒
      import task.{from,to}
      val registry = QAdapterRegistryKey.of(local)
      val protoToString = new ProtoToString(registry)
      def recode(stateOpt: Option[HasState]) = stateOpt.map{ state ⇒
        protoToString.recode(state.valueTypeId, state.value)
      }.getOrElse(("",""))
      val(fromSrcId,fromText) = recode(from)
      val(toSrcId,toText) = recode(to)
      val srcId = Single(List(fromSrcId,toSrcId).filter(_.nonEmpty).distinct)
      val delay = conn.outLongOption("upd")
        .in(Thread.currentThread.getName)
        .in(typeHex)
        .in(srcId)
        .in(fromText)
        .in(toText)
        .call().getOrElse(0L)

      logger.debug(s"from [$fromText] to [$toText] delay $delay")
      logger.warn(s"delay $delay")
      if(delay > 0L) RDBSleepUntilKey.modify(m ⇒
        m + (task.srcId→(now.plusMillis(delay)→to))
      )(local)
      else RDBSleepUntilKey.modify(m ⇒
        m - task.srcId
      ).andThen(TxAdd(
        from.toList.flatMap(LEvent.delete) ++ to.toList.flatMap(LEvent.update)
      ))(local)
    }}.getOrElse(local)
  }
}



////

@protocol(ExchangeCat) object FromExternalDBProtocolBase   {
  @Id(0x0060) case class DBOffset(
    @Id(0x0061) srcId: String,
    @Id(0x0062) value: Long
  )
}

@assemble class FromExternalDBSyncAssembleBase   {
  def joinTxTransform(
    key: SrcId,
    first: Each[Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List("externalDBSync").map(k⇒k→FromExternalDBSyncTransform(k))
}

case class FromExternalDBSyncTransform(srcId:SrcId) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = WithJDBCKey.of(local){ conn ⇒
    val offset =
      ByPK(classOf[DBOffset]).of(local).getOrElse(srcId, DBOffset(srcId, 0L))
    logger.debug(s"offset $offset")//, By.srcId(classOf[Invoice]).of(world).size)
    val textEncoded = conn.outText("poll").in(srcId).in(offset.value).call()
    //val updateOffset = List(DBOffset(srcId, nextOffsetValue)).filter(offset!=_)
    //  .map(n⇒LEvent.add(LEvent.update(n)))
    if(textEncoded.isEmpty) local else {
      logger.debug(s"textEncoded $textEncoded")
      WriteModelAddKey.of(local)((new IndentedParser).toUpdates(textEncoded))(local)
    }
  }
}

////
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

object UniversalProtoAdapter extends ProtoAdapter[UniversalNodeImpl](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalNodeImpl]) {
  def encodedSize(value: UniversalNodeImpl): Int =
    value.props.map(_.encodedSize).sum
  def encode(writer: ProtoWriter, value: UniversalNodeImpl): Unit =
    value.props.foreach(_.encode(writer))
  def decode(reader: ProtoReader): UniversalNodeImpl = throw new Exception("not implemented")
}

object UniversalDeleteProtoAdapter extends ProtoAdapter[UniversalDeleteImpl](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalDeleteImpl]) {
  def encodedSize(value: UniversalDeleteImpl): Int = throw new Exception("Can't be called")
  def encode(writer: ProtoWriter, value: UniversalDeleteImpl): Unit = throw new Exception("Can't be called")
  def decode(reader: ProtoReader): UniversalDeleteImpl = throw new Exception("Can't be called")
}

class IndentedParser(
  splitter: Char = ' ', lineSplitter: String = "\n"
) {
  //@tailrec final
  private def parseProp(key: String, value: List[String]): UniversalProp = {
    val Array(xHex,handlerName) = key.split(splitter)
    val ("0x", hex) = xHex.splitAt(2)
    val tag = Integer.parseInt(hex, 16)
    handlerName match {
      case "Node" ⇒ UniversalPropImpl(tag,UniversalNodeImpl(parseProps(value, Nil)))(UniversalProtoAdapter)
      case "Delete" ⇒ UniversalPropImpl(tag,UniversalDeleteImpl(parseProps(value, Nil)))(UniversalDeleteProtoAdapter)
      case _ ⇒ RDBTypes.toUniversalProp(tag,handlerName,value.mkString(lineSplitter))
    }
  }
  private def parseProps(lines: List[String], res: List[UniversalProp]): List[UniversalProp] =
    if(lines.isEmpty) res.reverse else {
      val key = lines.head
      val value = lines.tail.takeWhile(_.head == splitter).map(_.tail)
      val left = lines.tail.drop(value.size)
      parseProps(left, parseProp(key, value) :: res)
    }

  private def getNodeSrcId(node: UniversalNode): SrcId =
    node.props.head.value match {
      case s: String ⇒ s
    }

  def toUpdates(textEncoded: String): List[Update] = {
    val lines = textEncoded.split(lineSplitter).filter(_.nonEmpty).toList
    val universalNode = UniversalNodeImpl(parseProps(lines, Nil))
    //println(PrettyProduct.encode(universalNode))
    universalNode.props.map { prop ⇒
      val (srcId, value) = prop.value match {
        case node: UniversalDeleteImpl ⇒ (getNodeSrcId(node), ToByteString(Array.emptyByteArray))
        case node: UniversalNode ⇒ (getNodeSrcId(node), ToByteString(prop.encodedValue))
      }
      Update(srcId, prop.tag, value, 0L)
    }
  }
}

class ProtoToString(registry: QAdapterRegistry){
  private def esc(id: Long, handler: String, value: String): String =
    s"\n${Hex(id)} $handler${value.replace("\n","\n ")}"
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
  def recode(valueTypeId: Long, value: ByteString): (String,String) =
    registry.byId.get(valueTypeId).map{ adapter ⇒
      val(srcId,decoded) = WithPK(adapter.decode(value.toByteArray))
      (srcId,encode(adapter.id, decoded))
    }.getOrElse(("",""))
}

////

object Hex { def apply(i: Long): String = "0x%04x".format(i) }

object RDBTypes {
  def encode(p: Object): String = p match {
    case v: String ⇒ v
    case v: java.lang.Boolean ⇒ if(v) "T" else ""
    case v: java.lang.Long ⇒ v.toString
    case v: BigDecimal ⇒ v.bigDecimal.toString
    case v: Instant ⇒ v.toEpochMilli.toString
  }
  def shortName(cl: Class[_]): String = cl.getName.split("\\.").last
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
      UniversalPropImpl(tag,UniversalNodeImpl(List(scaleProp,bytesProp)))(UniversalProtoAdapter)
  }
}
