package ee.cone.c4actor.ddl

import java.time.Instant

import ee.cone.c4actor._
import ee.cone.c4assemble.ReverseInsertionOrder

import scala.util.matching.Regex

object RDBTypes {
  private val msPerDay: Int = 24 * 60 * 60 * 1000
  private val epoch = "to_date('19700101','yyyymmdd')"
  //
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
}

class NeedDBOption(val need: Need) extends ExternalDBOption
class UserDBOption(val user: String) extends ExternalDBOption

class DDLGeneratorOptionFactoryImpl(util: DDLUtil) extends DDLGeneratorOptionFactory {
  def createOrReplace(key: String, args: String, code: String): ExternalDBOption =
    new NeedDBOption(util.createOrReplace(key,args,code))
  def grantExecute(key: String): ExternalDBOption = new NeedDBOption(GrantExecute(key))
  def dbUser(user: String): ExternalDBOption = new UserDBOption(user)
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
        s"${encoderName(prop.resultType)}(${HexStr(prop.id)}, rec.a${prop.propName})"
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
          bodyStatements(List(s"return ${encoderName(n)}(${HexStr(adapter.id)},rec);"))
        )
        f :: GrantExecute(sName) :: Nil
      }).flatten
      val handleExtIn = procToSql.toList.map(proc⇒
        hooks.function(toDbName(name,"r"), s"aRetry number, aFrom $tName, aTo $tName", "", proc)
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




  def generate() = {



  }

}

