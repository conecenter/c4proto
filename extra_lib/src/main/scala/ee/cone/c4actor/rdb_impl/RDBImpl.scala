package ee.cone.c4actor.rdb_impl

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

import FromExternalDBProtocol.B_DBOffset
import ToExternalDBProtocol.B_HasState
import ToExternalDBTypes.{NeedSrcId, PseudoOrigNeedSrcId}
import com.squareup.wire.ProtoAdapter
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, S_Firstborn}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.rdb_impl.ToExternalDBAssembleTypes.PseudoOrig
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4proto._
import okio.ByteString
import ee.cone.c4actor.rdb.{ExternalDBClient, _}
import ee.cone.c4actor.rdb_impl.ProtoIndentedParserError.S_IndentedParserError

@c4("FromExternalDBSyncApp") final class FromExternalDBOptionsProvider(
  rdbOptionFactory: RDBOptionFactory
) {
  @provide def get: Seq[ExternalDBOption] = List(
    rdbOptionFactory.fromDB(classOf[FromExternalDBProtocol.B_DBOffset])
  )
}

@c4("RDBSyncApp") final class RDBOptionFactoryImpl(
  toExternalDBOrigAssembleFactory: ToExternalDBOrigAssembleFactory
) extends RDBOptionFactory {
  def fromDB[P <: Product](cl: Class[P]): ExternalDBOption = new FromDBOption(cl)
  def toDB[P <: Product](cl: Class[P], code: List[String]): ExternalDBOption =
    new ToDBOption(cl, code, toExternalDBOrigAssembleFactory.create(cl))
}

////

@protocol("ToExternalDBSyncApp") object ToExternalDBProtocol   {
  @Id(0x0063) case class B_HasState(
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

@c4("ToExternalDBSyncApp") final class ToExternalDBAssemblesBase(options: List[ExternalDBOption]) {
  @provide def subAssembles: Seq[Assemble] = options.collect{ case o: ToDBOption => o.assemble }
}

object ToExternalDBAssembleTypes {
  type PseudoOrig = SrcId
}

trait  ToExternalDBItemAssembleUtil {
  def toUpdate: ToUpdate

  def itemToHasState[D_Item <: Product]: D_Item => Values[(String,B_HasState)] = item =>
    for(e <- LEvent.update(item)) yield {
      val u = toUpdate.toUpdate(e)
      val key = C4UUID.nameUUIDFromBytes(ToBytes(u.valueTypeId) ++ u.srcId.getBytes(UTF_8)).toString
      key -> B_HasState(key,u.valueTypeId,u.value)
    }
}

@c4multiAssemble("RDBSyncApp") class ToExternalDBOrigAssembleBase[D_Item<:Product](
  classItem: Class[D_Item]
)(
  val toUpdate: ToUpdate
)  extends   ToExternalDBItemAssembleUtil {
  def hasStateByNeedSrcIdJoiner(
    key: SrcId,
    item: Each[D_Item]
  ): Values[(NeedSrcId,B_HasState)] =
    itemToHasState(item)

  def PseudoOrigJoin(
    key: SrcId,
    @by[PseudoOrig] item: Each[D_Item]
  ): Values[(PseudoOrigNeedSrcId,B_HasState)] =
    itemToHasState(item)
}

@c4assemble("ToExternalDBSyncApp") class ToExternalDBTxAssembleBase(
  toExternalDBTxFactory: ToExternalDBTxFactory
) extends   LazyLogging{
  type TypeHex = String
  def joinTasks(
    key: SrcId,
    @by[PseudoOrigNeedSrcId] pseudoNeedStates: Values[B_HasState],
    @by[NeedSrcId] needStates: Values[B_HasState],
    hasStates: Values[B_HasState]
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
      List(typeHex -> ToExternalDBTask(key, typeHex, Single.option(hasStates), Single.option(mergedNeedStates)))
    }
  }
  def join(
    key: SrcId,
    @by[TypeHex] tasks: Values[ToExternalDBTask]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(toExternalDBTxFactory.create(key, tasks.toList)))
}

case class ToExternalDBTask(
  srcId: SrcId,
  typeHex: String,
  from: Option[B_HasState],
  to: Option[B_HasState]
)

case object RDBSleepUntilKey extends TransientLens[Map[SrcId,(Instant,Option[B_HasState])]](Map.empty)

@c4multi("ToExternalDBSyncApp") final case class ToExternalDBTx(typeHex: SrcId, tasks: List[ToExternalDBTask])(
  rDBTypes: RDBTypes,
  txAdd: LTxAdd,
  registry: QAdapterRegistry,
  externalDBClient: ExternalDBClient,
  externalIsActive: ExternalIsActive
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = if (externalIsActive.isActive) {
    val now = Instant.now()
    tasks.find{ task =>
      val skip = RDBSleepUntilKey.of(local)
      val(until,wasTo) = skip.getOrElse(task.srcId, (Instant.MIN,None))
      until.isBefore(now) || task.to != wasTo
    }.map{ task => externalDBClient.getConnectionPool.doWith { conn =>
      import task.{from,to}
      val protoToString = new ProtoToString(registry,rDBTypes)
      def recode(stateOpt: Option[B_HasState]) = stateOpt.map{ state =>
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
      if(delay > 0L) RDBSleepUntilKey.modify(m =>
        m + (task.srcId->(now.plusMillis(delay)->to))
      )(local)
      else RDBSleepUntilKey.modify(m =>
        m - task.srcId
      ).andThen(txAdd.add(
        from.toList.flatMap(LEvent.delete) ++ to.toList.flatMap(LEvent.update)
      ))(local)
    }}.getOrElse(local)
  } else local
}



////

@protocol("FromExternalDBSyncApp") object FromExternalDBProtocol   {
  @Id(0x0060) case class B_DBOffset(
    @Id(0x0061) srcId: String,
    @Id(0x0062) value: Long
  )
}

@c4assemble("FromExternalDBSyncApp") class FromExternalDBSyncAssembleBase(
  factory: FromExternalDBSyncTransformFactory
) {
  def joinTxTransform(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(factory.create("externalDBSync")))
}

@c4multi("FromExternalDBSyncApp") final case class FromExternalDBSyncTransform(srcId:SrcId)(
  indentedParser: IndentedParser,
  getB_DBOffset: GetByPK[B_DBOffset],
  rawTxAdd: RawTxAdd,
  externalDBClient: ExternalDBClient,
  externalIsActive: ExternalIsActive
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context =
    if (externalIsActive.isActive)
      externalDBClient.getConnectionPool.doWith { conn =>
        val offset =
          getB_DBOffset.ofA(local).getOrElse(srcId, B_DBOffset(srcId, 0L))
        logger.debug(s"offset $offset") //, By.srcId(classOf[Invoice]).of(world).size)
        val textEncoded = conn.outText("poll").in(srcId).in(offset.value).call()
        //val updateOffset = List(B_DBOffset(srcId, nextOffsetValue)).filter(offset!=_)
        //  .map(n=>LEvent.add(LEvent.update(n)))
        if (textEncoded.isEmpty) local else {
          logger.debug(s"textEncoded $textEncoded")
          rawTxAdd.add(indentedParser.toUpdates(textEncoded))(local)
        }
      }
    else local
}

@c4("FromExternalDBSyncApp") final class FromExternalDBUpdateFlag extends UpdateFlag {
  val flagValue: Long = 8L
}

@c4("FromExternalDBSyncApp") final class IndentedParser(
  universalProtoAdapter: ProtoAdapter[UniversalNode],
  rDBTypes: RDBTypes,
  universalNodeFactory: UniversalNodeFactory,
  fromExternalDBUpdateFlag: FromExternalDBUpdateFlag,
  errorAdapter: Option[ProtoAdapter[S_IndentedParserError]],
  hashGen: HashGen,
  splitter: Char = ' ', lineSplitter: String = "\n"
)(
  fromExternalDBFlag: Long = fromExternalDBUpdateFlag.flagValue
) extends LazyLogging {
  //@tailrec final
  private def parseProp(key: String, value: List[String]): UniversalProp = {
    import universalNodeFactory._
    val Array(xHex,handlerName) = key.split(splitter)
    val ("0x", hex) = xHex.splitAt(2)
    val tag = Integer.parseInt(hex, 16)
    handlerName match {
      case "Node" => prop[UniversalNode](tag,node(parseProps(value, Nil)),universalProtoAdapter)
      case "Delete" => prop(tag,UniversalDeleteImpl(parseProps(value, Nil)),UniversalDeleteProtoAdapter)
      case _ => rDBTypes.toUniversalProp(tag,handlerName,value.mkString(lineSplitter))
    }
  }
  @scala.annotation.tailrec
  private def parseProps(lines: List[String], res: List[UniversalProp]): List[UniversalProp] =
    if(lines.isEmpty) res.reverse else {
      val key = lines.head
      val value = lines.tail.takeWhile(_.head == splitter).map(_.tail)
      val left = lines.tail.drop(value.size)
      parseProps(left, parseProp(key, value) :: res)
    }

  private def getNodeSrcId(node: UniversalNode): Option[SrcId] =
    node.props.headOption.map(_.value).collect {
      case s: SrcId => s
    }

  def reportError(prop: UniversalProp, text: String): List[N_Update] =
    errorAdapter.map(adapter => {
      logger.error(s"Prop with errors: $prop from text $text")
      val errorTypeId = adapter.asInstanceOf[HasId].id
      val error = S_IndentedParserError(
        srcId = "",
        text = text,
        tag = prop.tag,
        value = prop.value.toString
      )
      val errorId = hashGen.generate(error)
      N_Update(
        srcId = errorId,
        valueTypeId = errorTypeId,
        value = ToByteString(
          adapter.encode(
            error.copy(srcId = errorId)
          )
        ),
        flags = 0L
      )
    }
    ).toList

  def toUpdates(textEncoded: String): List[N_Update] = {
    val lines = textEncoded.split(lineSplitter).filter(_.nonEmpty).toList
    val universalNode = universalNodeFactory.node(parseProps(lines, Nil))
    //println(PrettyProduct.encode(universalNode))
    universalNode.props.flatMap { prop =>
      val (srcIdOpt, value) = prop.value match {
        case node: UniversalDeleteImpl => (getNodeSrcId(node), ToByteString(Array.emptyByteArray))
        case node: UniversalNode => (getNodeSrcId(node), ToByteString(prop.encodedValue))
      }
      srcIdOpt match {
        case Some(srcId) => N_Update(srcId, prop.tag, value, fromExternalDBFlag) :: Nil
        case None => reportError(prop, textEncoded)
      }
    }
  }
}

@protocol("FromExternalDBSyncApp") object ProtoIndentedParserError {
  @Id(0x00A0) case class S_IndentedParserError(
    @Id(0x00A1) srcId: SrcId,
    @Id(0x00A2) text: String,
    @Id(0x00A3) tag: Int,
    @Id(0x00A4) value: String
  )
}

class ProtoToString(registry: QAdapterRegistry, rDBTypes: RDBTypes){
  private def esc(id: Long, handler: String, value: String): String =
    s"\n${Hex(id)} $handler${value.replace("\n","\n ")}"
  private def encode(id: Long, p: Any): String = p match {
    case Nil | None => ""
    case Some(e) => encode(id, e)
    case l: List[_] => l.map(encode(id, _)).mkString
    case p: Product if registry.byName.contains(p.getClass.getName)=>
      val adapter = registry.byName(p.getClass.getName)
      esc(id, "Node", adapter.props.zipWithIndex.map{
        case(prop,i) => encode(prop.id, p.productElement(i))
      }.mkString)
    case e: Object =>
      esc(id, rDBTypes.shortName(e.getClass), s"\n${rDBTypes.encode(e)}")
  }
  def recode(valueTypeId: Long, value: ByteString): (String,String) =
    registry.byId.get(valueTypeId).map{ adapter =>
      val(srcId,decoded) = WithPK(adapter.decode(value.toByteArray))
      (srcId,encode(adapter.id, decoded))
    }.getOrElse(("",""))
}

////

object Hex { def apply(i: Long): String = "0x%04x".format(i) }

@c4("RDBSyncApp") final class RDBTypes(
  universalProtoAdapter: ProtoAdapter[UniversalNode],
  universalNodeFactory: UniversalNodeFactory,
  srcIdProtoAdapter: ProtoAdapter[SrcId],
  customFieldAdapters: List[CustomFieldAdapter]
) {
  lazy val customFieldAdapterByType: Map[String, CustomFieldAdapter] =
    customFieldAdapters.map(adapter => shortName(adapter.supportedCl) -> adapter)
      .toMap

  import universalNodeFactory._
  def encode(p: Object): String = p match {
    case v: String => v
    // case v: SrcId => v in case SrcId becomes something other than String
    case v: java.lang.Boolean => if(v) "T" else ""
    case v: java.lang.Long => v.toString
    case v: java.lang.Integer => v.toString
    case v: BigDecimal => v.bigDecimal.toString
    case v: okio.ByteString => v.base64()
    case v: Instant => v.toEpochMilli.toString
    case other =>
      customFieldAdapterByType.get(shortName(other.getClass)) match {
        case Some(adapter) => adapter.encode(other)
        case None => FailWith(s"Unsupported encode type ${other.getClass.getName}")
      }
  }
  def shortName(cl: Class[_]): String = cl.getName.split("\\.").last

  def toUniversalProp(tag: Int, typeName: String, value: String): UniversalProp = typeName match {
    case "String" =>
      prop[String](tag,value,ProtoAdapter.STRING)
    case "SrcId" =>
      prop[SrcId](tag,value,srcIdProtoAdapter)
    case "ByteString" =>
      prop[okio.ByteString](tag, okio.ByteString.decodeBase64(value),ProtoAdapter.BYTES)
    case "Boolean" =>
      prop[java.lang.Boolean](tag,value.nonEmpty,ProtoAdapter.BOOL)
    case "Long" | "Instant" =>
      prop[java.lang.Long](tag,java.lang.Long.parseLong(value),ProtoAdapter.SINT64)
    case "Integer" =>
      prop[java.lang.Integer](tag,java.lang.Integer.parseInt(value),ProtoAdapter.SINT32)
    case "BigDecimal" =>
      val BigDecimalFactory(scale,bytes) = BigDecimal(value)
      val scaleProp = prop(0x0001,scale:Integer,ProtoAdapter.SINT32)
      val bytesProp = prop(0x0002,bytes,ProtoAdapter.BYTES)
      prop[UniversalNode](tag,node(List(scaleProp,bytesProp)),universalProtoAdapter)
    case other => customFieldAdapterByType.get(other) match {
      case Some(adapter) => adapter.toUniversalProp(tag, value)
      case None => FailWith(s"Unsupported toUniversalProp type ${other}")
    }
  }
}

///

object UniversalDeleteProtoAdapter extends ProtoAdapter[UniversalDeleteImpl](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalDeleteImpl]) {
  def redact(e: UniversalDeleteImpl): UniversalDeleteImpl = throw new Exception("Can't be called")
  def encodedSize(value: UniversalDeleteImpl): Int = throw new Exception("Can't be called")
  def encode(writer: ProtoWriter, value: UniversalDeleteImpl): Unit = throw new Exception("Can't be called")
  def decode(reader: ProtoReader): UniversalDeleteImpl = throw new Exception("Can't be called")
}

case class UniversalDeleteImpl(props: List[UniversalProp]) extends UniversalNode
