package ee.cone.c4actor.rdb_impl

import com.squareup.wire.{FieldEncoding, ProtoAdapter, ProtoReader, ProtoWriter}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.PreparedStatement
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

import scala.util.matching.Regex

@protocol object FromExternalDBProtocol extends c4proto.Protocol {
  @Id(0x0060) case class DBOffset(
    @Id(0x0061) srcId: String,
    @Id(0x0062) value: Long
  )
}

@protocol object ToExternalDBProtocol extends c4proto.Protocol {
  @Id(0x0063) case class HasState(
    @Id(0x0061) srcId: String,
    @Id(0x0064) valueTypeId: Long,
    @Id(0x0065) value: okio.ByteString
  )
}



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

object ToExternalDBAssembles {
  def apply(registry: QAdapterRegistry, options: List[ExternalDBOption]): List[Assemble] =
    new ToExternalDBTxAssemble ::
      options.collect{ case o: ToDBOption ⇒ o.assemble }
}

object ToBytes {
  def apply(value: Long): Array[Byte] =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
}

object ToExternalDBTypes {
  type NeedSrcId = SrcId
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

object RDBTypes {
  val msPerDay: Int = 24 * 60 * 60 * 1000
  val epoch = "to_date('19700101','yyyymmdd')"
  //
  private def bind(code: String, v: Object) = (code,Option(v)) :: Nil
  def toStatement(p: Any): List[(String,Option[Object])] = {
    case v: String ⇒ bind("?",v)
    case v: java.lang.Boolean ⇒ bind("?",v)
    case v: java.lang.Long ⇒ bind("?",v)
    case v: BigDecimal ⇒ bind("?",v.bigDecimal)
    case v: Instant ⇒ bind(s"$epoch + (?/$msPerDay)",new java.lang.Long(v.toEpochMilli))
  }
  //
  def sysTypes: List[String] = List(
    classOf[String],
    classOf[java.lang.Boolean],
    classOf[java.lang.Long],
    classOf[BigDecimal],
    classOf[Instant]
  ).map(_.getName.split("\\.").last)
  //
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



case class ToExternalDBTx(srcId: SrcId, from: Option[HasState], to: Option[HasState]) extends TxTransform {
  private type Part = (String,Option[Object])
  private def function(name: String, args: List[List[Part]]): List[Part] =
    (name,None) :: args.zipWithIndex.flatMap{
      case(el,idx) ⇒ (if(idx==0) "(" else ",", None) :: el
    } ::: (")",None) :: Nil

  private def commonName(args: List[List[Part]]) =
    Single(args.map{
      case (name,None) :: _ ⇒ name
      case _ ⇒ throw new Exception
    }.filter(_!="null").distinct)
  private def toStatement(p: Any): List[Part] = p match {
    case Nil | None ⇒ ("null",None) :: Nil
    case Some(e) ⇒ toStatement(e)
    case l: List[_] ⇒
      val children = l.map(toStatement)
      function(s"t${commonName(children).drop(1)}s", children) //???compat
    case p: Product ⇒
      val name = s"b${p.productPrefix.split("\\$").last}"
      function(name, p.productIterator.map(toStatement).toList)
    case e ⇒ RDBTypes.toStatement(e)
  }

  def transform(local: World): World = WithJDBCKey.of(local){ conn ⇒
    val registry = QAdapterRegistryKey.of(local)()
    def decode(states: Option[HasState]): Option[Product] = states.flatMap{ state ⇒
      registry.byId.get(state.valueTypeId).map(_.decode(state.value.toByteArray))
    }
    val roots = List(decode(from),decode(to)).map(toStatement)
    val fun = function(s"r${commonName(roots).drop(1)}", roots)
    val code = s"begin ${fun.map(_._1).mkString}; end;"
    val binds = fun.flatMap(_._2)
    println(code, binds)
    conn.execute(code, binds)
    LEvent.add(
      from.toList.flatMap(LEvent.delete) ++ to.toList.flatMap(LEvent.update)
    )(local)
  }
}

//

@assemble class FromExternalDBSyncAssemble extends Assemble {
  def joinTxTransform(
    key: SrcId,
    offset: Values[Offset]
  ): Values[(SrcId,TxTransform)] =
    List("externalDBSync").map(k⇒k→FromExternalDBSyncTransform(k))
}

import java.lang.Math.toIntExact

case class FromExternalDBSyncTransform(srcId:SrcId) extends TxTransform {
  def transform(local: World): World = WithJDBCKey.of(local){ conn ⇒
    val world = TxKey.of(local).world
    val offset: DBOffset =
      Single.option(By.srcId(classOf[DBOffset]).of(world).getOrElse(srcId,Nil))
        .getOrElse(DBOffset(srcId, 0L))
    //println("offset",offset)//, By.srcId(classOf[Invoice]).of(world).size)
    val messages = conn.executeQuery(
      "select offset, content from outbox where offset >= ? order by offset",
      List("offset", "content"), List(offset.value:java.lang.Long)
    )
    println(messages.size,"messages from db")
    if(messages.isEmpty) local else {
      val nextOffset = offset.copy(value = messages.map(_("offset") match {
        //case v: java.lang.Long ⇒ v
        case v: java.math.BigDecimal ⇒ v.longValueExact
      }).max + 1L)
      println(nextOffset)
      val textEncoded = messages.map(_("content") match {
        case c: java.sql.Clob ⇒ c.getSubString(1,toIntExact(c.length()))
      }).mkString
      val lineSplitter = "\n"
      val parser = new IndentedParser(' ',lineSplitter,RDBTypes.toUniversalProp)
      val lines = textEncoded.split(lineSplitter).filter(_.nonEmpty).toList
      val universalNode = UniversalNode(parser.parseProps(lines, Nil))
      //println(PrettyProduct.encode(universalNode))
      val updateList = universalNode.props.map{ prop ⇒
        val srcId = prop.value match {
          case node: UniversalNode ⇒ node.props.head.value match {
            case s: String ⇒ s
          }
        }
        Update(srcId, prop.tag, ToByteString(prop.encodedValue))
      }
      Function.chain(List[World⇒World](
        TxKey.modify(_.add(updateList)),
        LEvent.add(LEvent.update(nextOffset))
      ))(local)
    }
  }
}

case object WithJDBCKey extends WorldKey[(RConnection⇒World)⇒World](_⇒throw new Exception)

class ExternalDBSyncClient(
  dbFactory: ExternalDBFactory,
  db: CompletableFuture[RConnectionPool] = new CompletableFuture() //dataSource: javax.sql.DataSource
) extends InitLocal with Executable {
  def initLocal: World ⇒ World = WithJDBCKey.set(db.get.doWith)
  def run(ctx: ExecutionContext): Unit = db.complete(dbFactory.create(
    createConnection ⇒ new RConnectionPool {
      def doWith[T](f: RConnection⇒T): T = {
        FinallyClose(createConnection()) { sqlConn ⇒
          val conn = new RConnectionImpl(sqlConn)
          f(conn)
        }
      }
    }
  ))
}

object FinallyClose {
  def apply[A<:AutoCloseable,T](o: A)(f: A⇒T): T = try f(o) finally o.close()
}

class RConnectionImpl(conn: java.sql.Connection) extends RConnection {
  private def bindObjects(stmt: java.sql.PreparedStatement, bind: List[Object]) =
    bind.zipWithIndex.foreach{ case (v,i) ⇒ stmt.setObject(i+1,v) }
      /*
      case (v:String, i:Int) ⇒ stmt.setString(i+1, v)
      case (v:java.lang.Boolean, i:Int) ⇒ stmt.setBoolean(i+1, v)
      case (v:java.lang.Long, i:Int) ⇒ stmt.setLong(i+1, v)
      case (v:BigDecimal, i:Int) ⇒ stmt.setBigDecimal(i+1, v.bigDecimal)*/

  def execute(code: String, bind: List[Object]): Unit =
    FinallyClose(conn.prepareStatement(code)){ stmt ⇒
      bindObjects(stmt, bind)
      //println(code,bind)
      stmt.execute()
      //println(stmt.getWarnings)
    }

  def executeQuery(code: String, cols: List[String], bind: List[Object]): List[Map[String,Object]] = {
    //println(s"code:: [$code]")
    FinallyClose(conn.prepareStatement(code)) { stmt ⇒
      bindObjects(stmt, bind)
      FinallyClose(stmt.executeQuery()) { rs ⇒
        var res: List[Map[String, Object]] = Nil
        while(rs.next()) res = cols.map(cn ⇒ cn → rs.getObject(cn)).toMap :: res
        res.reverse
      }
    }
  }
}

case class NeedType(drop: DropType, ddl: String) extends Need

object DDLUtilImpl extends DDLUtil {
  def createOrReplace(key: String, args: String, code: String): NeedCode =
    NeedCode(key.toLowerCase, s"create or replace $key${if(args.isEmpty) "" else s"($args)"} $code")
}

class DDLGeneratorImpl(
  options: List[ExternalDBOption],
  hooks: DDLGeneratorHooks,
  MType: Regex = """(\w+)\[(\w+)\]""".r
) extends DDLGenerator {

  private def toDbName(tp: String, mod: String, size: Int=0): String = hooks.toDbName(tp match {
    case MType("List", t) ⇒ s"${t}s"
    case MType("Option", t) ⇒ t
    case t ⇒ t
  }, mod, size)

  private def uniqueBy[K,T](l: List[T])(f: T⇒K): Map[K, T] = l.groupBy(f).transform{ (k,v) ⇒
    if(v.size > 1) throw new Exception(s"$k is redefined")
    v.head
  }
  protected def bodyStatements(statements: List[String]): String =
    s"begin\n${statements.map(l⇒s"  $l\n").mkString("")}end;"
  private def returnIfNull = "if rec is null then return ''; end if;"
  private def esc(handler: String, expr: String) =
    bodyStatements(returnIfNull :: s"return chr(10) || key || ' ' || '$handler' || replace($expr, chr(10), chr(10)||' ');" :: Nil)
  private def getId(i: Long) = "'0x%04x'".format(i)
  private def longStrType = toDbName("String","t",1)
  private def encoderName(dType: String) = toDbName(dType,"e")
  private def encode(name: String, body: String) =
    hooks.function(encoderName(name),s"key ${toDbName("String","t")}, ${recArg(name)}",longStrType,body)
  private def recArg(name: String) = s"rec ${toDbName(name,"t")}"

  def generate(
    wasTypes: List[DropType],
    wasFunctionNameList: List[String]
  ): List[String] = {
    val setFromSql: Set[String] =
      uniqueBy(options.collect{ case o: FromDBOption ⇒ o.className })(i⇒i).keySet
    val mapToSql: Map[String, String] =
      uniqueBy(options.collect{ case o: ToDBOption ⇒ o })(_.className)
        .transform((k,v)⇒v.code)
    val adapters = options.collect{ case o: ProtocolDBOption ⇒ o.protocol.adapters }.flatten
    val notFound = (setFromSql ++ mapToSql.keySet) -- adapters.map(_.className)
    if(notFound.nonEmpty) throw new Exception(s"adapters was not found: $notFound")
    //
    val needs: List[Need] = options.collect{
      case o: NeedDBOption ⇒ o.need
    } ::: adapters.flatMap{ adapter ⇒
      val props = adapter.props
      val className = adapter.className
      val name = className.split("\\$").last
      val names = s"List[$name]"
      val isFromSql = setFromSql(className)
      val procToSql = mapToSql.get(className)
      val tName = toDbName(name, "t")
      val tNames = toDbName(names,"t")
      //
      val encodeArgs = props.map{ prop ⇒
        s"${encoderName(prop.resultType)}(${getId(prop.id)}, rec.a${prop.propName})"
      }.mkString(" || ")
      val encodeNode = encode(name, esc("Node", encodeArgs))
      val encodeNodes = encode(names, s"res $longStrType;\n${bodyStatements(List(
        returnIfNull,
        hooks.loop(encoderName(name)),
        "return res;"
      ))}")
      //
      val encodeExtOut = (for(n ← List(name) if isFromSql) yield {
        val sName = toDbName(n,"s")
        val f = hooks.function(sName, recArg(n), longStrType,
          bodyStatements(List(s"return ${encoderName(n)}(${getId(adapter.id)},rec);"))
        )
        f :: GrantExecute(sName) :: Nil
      }).flatten
      val handleExtIn = procToSql.toList.map(proc⇒
        hooks.function(toDbName(name,"r"), s"aFrom $tName, aTo $tName", "", proc)
      )
      //
      def theType(t: String, attr: List[(String,String)], body: String) = List(
        GrantExecute(t),
        NeedType(
          DropType(
            t.toLowerCase,
            attr.map{ case (a,at) ⇒ DropTypeAttr(a.toLowerCase, at.toLowerCase) },
            Nil
          ),
          s"create type $t as $body;"
        )
      )
      val attrs = props.map(prop ⇒ (s"a${prop.propName}", toDbName(prop.resultType, "t", -1)))
      val attrStr = attrs.map{ case(a,at) ⇒ s"$a $at" }.mkString(", ")
      val needType = theType(tName,attrs,s"${hooks.objectType}($attrStr)")
      val needTypes = if(tNames.endsWith("[]")) Nil
        else theType(tNames, List(("",tName)), s"table of $tName")
      val constructorArgs =
        props.map(prop ⇒ s"a${prop.propName} ${toDbName(prop.resultType, "t")} default null")
          .mkString(", ")
      val defaultArgs =
        attrs.map{case (n,t)⇒ RDBTypes.constructorToTypeArg(t)(n)}
          .mkString(", ")
      val constructor = hooks.function(toDbName(name, "b"), constructorArgs, tName, bodyStatements(List(
        s"return $tName($defaultArgs);"
      )))
      //
      needType ::: needTypes ::: constructor :: encodeNode :: encodeNodes :: encodeExtOut ::: handleExtIn
    }
    //
    val needCTypes = needs.collect{ case t:NeedType ⇒ t }
    val needTypes: List[DropType] = needCTypes.map(_.drop)
    val ddlForType = needCTypes.map(t⇒t.drop→t.ddl).toMap
    val (needTypesFull,needTypesSet) = orderedTypes(needTypes)
    val (wasTypesFull,wasTypesSet) = orderedTypes(wasTypes)
    val dropTypes =
      wasTypesFull.filterNot(needTypesSet).map(t ⇒ s"drop type ${t.name}")
    val createTypes =
      needTypesFull.filterNot(wasTypesSet).reverse.map(t ⇒ ddlForType(t.copy(uses=Nil)))
    //
    val needFunctions =
        RDBTypes.sysTypes.map(t⇒encode(t, esc(t, s"chr(10) || ${RDBTypes.encodeExpression(t)("rec")}"))) :::
        needs.collect{ case f: NeedCode ⇒ f }
    val needFunctionNames = uniqueBy(needFunctions)(_.drop).keySet
    val replaceFunctions =
      wasFunctionNameList.filterNot(needFunctionNames).map(n ⇒ s"drop $n") :::
        needFunctions.map(f ⇒ f.ddl)
    //
    val grants = for(
      user ← options.collect{ case o: UserDBOption ⇒ o.user };
      obj ← needs.collect{ case GrantExecute(n) ⇒ n }
    ) yield s"grant execute on $obj to $user"
    //
    (dropTypes ::: createTypes ::: replaceFunctions ::: grants).map(l⇒s"$l")
  }
  private def orderedTypes(needTypesList: List[DropType]): (List[DropType],Set[DropType]) = {
    val needTypes = uniqueBy(needTypesList)(_.name)
    val isSysType = RDBTypes.sysTypes.map(t⇒toDbName(t,"t").toLowerCase).toSet
    def regType(res: ReverseInsertionOrder[String,DropType], name: String): ReverseInsertionOrder[String,DropType] = {
      if(res.map.contains(name)) res else {
        val needType = needTypes(name)
        //println(needType)
        val useNames = needType.attributes.map(a ⇒
          a.attrTypeName.split("[\\[\\(]").head
        ).filterNot(isSysType)
        val resWithUses = (res /: useNames)(regType)
        resWithUses.add(name, needType.copy(uses=useNames.map(resWithUses.map)))
      }
    }
    val ordered = (ReverseInsertionOrder[String,DropType]() /: needTypesList.map(_.name))(regType).values //complex first
    (ordered,ordered.toSet)
  }
}





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
  splitter: Char, lineSplitter: String,
  toUniversalProp: (Int,String,String)⇒UniversalProp
) {
  //@tailrec final
  private def parseProp(key: String, value: List[String]): UniversalProp = {
    val Array(xHex,handlerName) = key.split(splitter)
    val ("0x", hex) = xHex.splitAt(2)
    val tag = Integer.parseInt(hex, 16)
    if(handlerName != "Node") toUniversalProp(tag,handlerName,value.mkString(lineSplitter))
    else UniversalPropImpl(tag,UniversalNode(parseProps(value, Nil)))(UniversalProtoAdapter)
  }
  def parseProps(lines: List[String], res: List[UniversalProp]): List[UniversalProp] =
    if(lines.isEmpty) res.reverse else {
      val key = lines.head
      val value = lines.tail.takeWhile(_.head == splitter).map(_.tail)
      val left = lines.tail.drop(value.size)
      parseProps(left, parseProp(key, value) :: res)
    }
}


//protobuf universal draft