package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait JRule extends Product
case class JStat(content: String) extends JRule
case class JoinDef(params: Seq[JConnDef], inKeyType: KeyNSType, out: JConnDef) extends JRule
case class JConnDef(name: String, indexKeyName: String, inValOuterType: String, many: Boolean, distinct: Boolean, keyEq: Option[String])
case class KeyValType(name: String, of: List[KeyValType])
case class KeyNSType(key: KeyValType, str: String, ns: String)
case class SubAssembleName(name: String) extends JRule

object ExtractKeyValType {
  def unapply(t: Any): Option[KeyValType] = t match {
    case Some(e) ⇒ unapply(e)
    case Type.Name(n) ⇒ Option(KeyValType(n,Nil))
    case Type.Apply(Type.Name(n), types:Seq[_]) ⇒ Option(KeyValType(n, types.map(unapply(_).get).toList))
    case s: Tree ⇒ throw new Exception(s"${s.structure}")
  }
}
object ExtractKeyNSType {
  def unapply(t: Any): Option[KeyNSType] = t match {
    case Some(e) ⇒ unapply(e)
    case t: Tree ⇒ t match {
      case t"$tp @ns($nsExpr)" ⇒
        ExtractKeyValType.unapply(tp).map(kvt⇒KeyNSType(kvt, s"$tp", s"$nsExpr"))
      case tp ⇒
        ExtractKeyValType.unapply(tp).map(kvt⇒KeyNSType(kvt, s"$tp", ""))
    }
  }
}

object AssembleGenerator extends Generator {
  def get: Get = { case (code@q"@assemble class ${baseClassNameNode@Type.Name(baseClassName)} [..$tparams] (...$paramss) extends ..$ext { ..$stats }", fileName) ⇒
    Util.unBase(baseClassName,baseClassNameNode.pos.end){className ⇒
    val classArgs = paramss.toList.flatten.collect{
      case param"..$mods ${Term.Name(argName)}: Class[${Type.Name(typeName)}]" ⇒
        typeName -> argName
    }.toMap
    def mkLazyVal(name: String, body: String): JStat =
      JStat(s"private lazy val $name = {$body}")
    def classOfExpr(className: String) =
      classArgs.getOrElse(className,s"classOf[$className]") + ".getName"
    def classOfT(kvType: KeyValType): String =
      if(kvType.of.isEmpty) classOfExpr(kvType.name)
      else s"classOf[${kvType.name}[${kvType.of.map(_ ⇒ "_").mkString(", ")}]].getName+'['+${kvType.of.map(classOfT).mkString("+','+")}+']'"
    def joinKey(name: String, was: Boolean, key: KeyNSType, value: KeyValType): JStat = {
      val addNS = if(key.ns.isEmpty) "" else s""" + "#" + (${key.ns}) """
      JStat(s"""private def $name: MakeJoinKey = _.util.joinKey($was, "${key.str}"$addNS, ${classOfT(key.key)}, ${classOfT(value)})""")
    }
    def joinKeyB(name: String, body: String): JStat = {
      JStat(s"""private def $name: MakeJoinKey = $body """)
    }
    val rules: List[JRule] = stats.toList.flatMap {
      case q"type $tname = $tpe" ⇒ Nil
      case q"type $tname[..$params] = $tpe" ⇒ Nil
      case q"import ..$i" ⇒ Nil
      case e@q"..$mods val ..$vname: $tpe = $expr" ⇒ throw new Exception(s"Don't use val in Assemble: ${e.toString}")
      case q"@ignore def $ename[..$tparams](...$paramss): $tpeopt = $expr" ⇒ Nil
      case q"def result: Result = tupled(${Term.Name(joinerName)} _)" ⇒
        JStat(s"override def resultKey = ${joinerName}_outKey") :: Nil
      case q"def result: Result = $temp" ⇒ Nil
      case q"override def subAssembles: $tpeopt = $expr" ⇒
        expr.collect{
          case q"super.subAssembles" ⇒ List("ok")
          case _ ⇒ Nil
        }.flatten.headOption.map(_ ⇒ Nil).getOrElse(throw new Exception(s"\'override def subAssembles\' doesnt have \'super.subAssembles\'"))
      case q"def ${Term.Name(defName)}(...${Seq(params)}): Values[(${ExtractKeyNSType(outKeyType)},${ExtractKeyValType(outValType)})] = $expr" ⇒
        val param"$keyName: ${ExtractKeyNSType(inKeyType)}" = params.head
        val paramInfo: List[(JConnDef,List[JRule])] = params.tail.toList.map{
          case param"..$mods ${Term.Name(paramName)}: $inValOuterTypeOpt = $defVal" ⇒
            val Some(inValOuterType) = inValOuterTypeOpt
            val t"$manyT[${ExtractKeyValType(inValType)}]" = inValOuterType
            val many = manyT match { case t"Values" ⇒ true case t"Each" ⇒ false }
            //
            object DistinctAnn
            object WasAnn
            class ByAnn(val keyType: KeyNSType, val keyEq: Option[String])
            val ann = mods.map{
              case mod"@distinct" ⇒ DistinctAnn
              case mod"@was" ⇒ WasAnn
              case mod"@by[${ExtractKeyNSType(tp)}]" ⇒ new ByAnn(tp,None)
              case mod"@byEq[${ExtractKeyNSType(tp)}]($v)" ⇒ new ByAnn(tp,Option(s"$v"))
              case s ⇒ throw new Exception(s"${s.structure}")
            }
            val distinct = ann.contains(DistinctAnn)
            val was = ann.contains(WasAnn)
            val byOpt = ann.collect{ case b: ByAnn ⇒ b } match {
              case Seq(tp) ⇒ Option(tp)
              case Seq() ⇒ None
            }
            val keyEq: Option[String] = for {
              by ← byOpt
              keyEq ← by.keyEq
            } yield keyEq
            //
            val fullNamePrefix = s"${defName}_$paramName"
            val fullName = s"${fullNamePrefix}_inKey"
            val statements = defVal match {
              case None ⇒
                val by = byOpt.getOrElse(new ByAnn(inKeyType,None))
                joinKey(fullName, was, by.keyType , inValType) :: Nil
              case Some(q"$expr.call") ⇒
                assert(!was)
                assert(byOpt.isEmpty)
                val subAssembleName = s"${fullNamePrefix}_subAssemble"
                SubAssembleName(subAssembleName) ::
                mkLazyVal(subAssembleName,s"$expr") ::
                joinKeyB(fullName, s"$subAssembleName.resultKey") :: Nil
            }
            (JConnDef(paramName, fullName, s"$inValOuterType", many, distinct, keyEq),statements)
        }
        val joinDefParams = paramInfo.map(_._1)
        val fullName = s"${defName}_outKey"
        joinKey(fullName,was=false,outKeyType,outValType) ::
        JoinDef(joinDefParams,inKeyType,JConnDef(defName,fullName,"",many=false,distinct=false,None)) :: paramInfo.flatMap(_._2)
      case s: Tree ⇒ Utils.parseError(s, "assemble", fileName)
    }
    val toString =
      s"""getClass.getPackage.getName + ".$className" ${if(tparams.isEmpty)"" else {
        s""" + '['+ ${tparams.map(i ⇒
          classArgs.get(s"${i.name}").fold(s""" "${i.name}" """)(_+".getSimpleName")
        ).mkString("+','+")} +']'"""
      }}"""
    val joinImpl = rules.collect{
      case JoinDef(params,inKeyType,out) ⇒
        val (seqParams,eachParams) = params.partition(_.many)
        val (keyEqParams,keyIdParams) = params.partition(_.keyEq.nonEmpty)
        def litOrId(p: JConnDef): String = p.keyEq.getOrElse("id")
        s"""  private class ${out.name}_Join(indexFactory: IndexFactory) extends Join(
           |    $toString,
           |    "${out.name}",
           |    collection.immutable.Seq(${params.map(p⇒s"${p.indexKeyName}(indexFactory)").mkString(",")}),
           |    ${out.indexKeyName}(indexFactory)
           |  ) {
           |    def joins(diffIndexRawSeq: DiffIndexRawSeq, executionContext: OuterExecutionContext): Result = {
           |      implicit val ec = executionContext.value
           |      val iUtil = indexFactory.util
           |      val Seq(${params.map(p⇒s"${p.name}_diffIndex").mkString(",")}) = diffIndexRawSeq
           |      ${keyEqParams.map(p⇒s"val ${p.name}_isAllChanged = iUtil.nonEmpty(${p.name}_diffIndex,${litOrId(p)}); ").mkString}
           |      val invalidateKeySetOpt =
           |          ${if(keyEqParams.isEmpty)"" else s"""if(${keyEqParams.map(p⇒s"${p.name}_isAllChanged").mkString(" || ")}) None else """}
           |          Option(iUtil.keyIteration(Seq(${keyIdParams.map(p⇒s"${p.name}_diffIndex").mkString(",")}),executionContext))
           |      ${params.map(p ⇒ if(p.distinct) s"""val ${p.name}_warn = "";""" else s"""val ${p.name}_warn = "${out.name} ${p.name} "+${p.indexKeyName}(indexFactory).valueClassName;""").mkString}
           |      (dir,indexRawSeq) => {
           |        val Seq(${params.map(p⇒s"${p.name}_index").mkString(",")}) = indexRawSeq
           |        invalidateKeySetOpt.getOrElse(iUtil.keyIteration(Seq(${keyIdParams.map(p⇒s"${p.name}_index").mkString(",")}),executionContext)){ id =>
           |        ${seqParams.map(p⇒s"val ${p.name}_arg = iUtil.getValues(${p.name}_index,${litOrId(p)},${p.name}_warn); ").mkString}
           |        ${seqParams.map(p⇒s"val ${p.name}_isChanged = iUtil.nonEmpty(${p.name}_diffIndex,${litOrId(p)}); ").mkString}
           |        ${eachParams.map(p⇒s"val ${p.name}_parts = iUtil.partition(${p.name}_index,${p.name}_diffIndex,${litOrId(p)},${p.name}_warn); ").mkString}
           |          for {
           |            ${eachParams.map(p⇒s" ${p.name}_part <- ${p.name}_parts; (${p.name}_isChanged,${p.name}_items) = ${p.name}_part; ").mkString}
           |            pass <- if(
           |              ${if(eachParams.exists(_.keyEq.isEmpty))"" else seqParams.filter(_.keyEq.isEmpty).map(p⇒s"${p.name}_arg.nonEmpty").mkString("("," || ",") && ")}
           |              (${params.map(p⇒s"${p.name}_isChanged").mkString(" || ")})
           |            ) iUtil.nonEmptySeq else Nil;
           |            ${eachParams.map(p⇒s"${p.name}_arg <- ${p.name}_items(); ").mkString}
           |            pair <- ${out.name}(id.asInstanceOf[${inKeyType.str}],${params.map(p⇒s"${p.name}_arg.asInstanceOf[${p.inValOuterType}]").mkString(",")})
           |          } yield {
           |            val (byKey,product) = pair
           |            iUtil.result(byKey,product,dir)
           |          }
           |        }
           |      }
           |    }
           |  }
           |""".stripMargin
    }.mkString
    val dataDependencies = rules.collect {
      case d: JoinDef ⇒ s"new ${d.out.name}_Join(indexFactory)"
    }.mkString(s"  def dataDependencies = indexFactory ⇒ List(",",",").map(indexFactory.createJoinMapIndex)\n")
    val statRules = rules.collect{ case JStat(c) ⇒ s"  $c\n" }.mkString



    val (subAssembleWith,subAssembleDef) = (rules.collect{ case SubAssembleName(n) ⇒ n }.distinct) match {
      case Seq() ⇒ ("","")
      case s ⇒ (" with ee.cone.c4assemble.CallerAssemble",s.mkString(s"override def subAssembles = List(",",",") ::: super.subAssembles\n"))
    }

    val paramNames = paramss.map(params⇒params.map{
      case param"..$mods $name: $tpeopt = $expropt" ⇒ if(expropt.isEmpty) Option(name) else None
    }.flatten.mkString("(",",",")")).mkString
    val paramNamesWithTypes = paramss.map(params⇒params.map{
      case param"..$mods $name: $tpeopt = $expropt" ⇒ if(expropt.isEmpty) Option(param"..${mods.collect{ case mod"valparam" ⇒ None case o ⇒ Option(o) }.flatten} $name: $tpeopt") else None
    }.flatten)
    val res = q"""class ${Type.Name(className)} [..$tparams] (...$paramNamesWithTypes)"""

    //cont.substring(0,className.pos.end) + "_Base" + cont.substring(className.pos.end) +
    List(GeneratedCode(
      s"${res.syntax} extends ${baseClassName}$paramNames with Assemble$subAssembleWith " +
      s"{\n$statRules$joinImpl$dataDependencies$subAssembleDef}"
    ))
  }}
}
