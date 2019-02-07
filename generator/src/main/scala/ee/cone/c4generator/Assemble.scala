package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait JRule extends Product
case class JStat(content: String) extends JRule
case class JoinDef(params: Seq[JConnDef], inKeyType: KeyNSType, out: JConnDef) extends JRule
case class JConnDef(name: String, indexKeyName: String, inValOuterType: String, many: Boolean, distinct: Boolean)
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
  def get: Get = { case code@q"@assemble class ${Type.Name(baseClassName)} [..$tparams] (...$paramss) extends ..$ext { ..$stats }" ⇒ Util.unBase(baseClassName){className ⇒
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
      case q"import ..$i" ⇒ Nil
      case q"def result: Result = tupled(${Term.Name(joinerName)} _)" ⇒
        JStat(s"override def resultKey = ${joinerName}_outKey") :: Nil
      case q"def result: Result = $temp" ⇒ Nil
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
            class ByAnn(val keyType: KeyNSType)
            val ann = mods.map{
              case mod"@distinct" ⇒ DistinctAnn
              case mod"@was" ⇒ WasAnn
              case mod"@by[${ExtractKeyNSType(tp)}]" ⇒ new ByAnn(tp)
              case s ⇒ throw new Exception(s"${s.structure}")
            }
            val distinct = ann.contains(DistinctAnn)
            val was = ann.contains(WasAnn)
            val byOpt = ann.collect{ case b: ByAnn ⇒ b.keyType } match {
              case Seq(tp) ⇒ Option(tp)
              case Seq() ⇒ None
            }
            //
            val fullNamePrefix = s"${defName}_$paramName"
            val fullName = s"${fullNamePrefix}_inKey"
            val statements = defVal match {
              case None ⇒
              joinKey(fullName, was, byOpt.getOrElse(inKeyType), inValType) :: Nil
             case Some(q"$expr.call") ⇒
              assert(!was)
              assert(byOpt.isEmpty)
              val subAssembleName = s"${fullNamePrefix}_subAssemble"
              SubAssembleName(subAssembleName) ::
              mkLazyVal(subAssembleName,s"$expr") ::
              joinKeyB(fullName, s"$subAssembleName.resultKey") :: Nil
            }
            (JConnDef(paramName, fullName, s"$inValOuterType", many, distinct),statements)
        }
        val joinDefParams = paramInfo.map(_._1)
        val fullName = s"${defName}_outKey"
        joinKey(fullName,was=false,outKeyType,outValType) ::
        JoinDef(joinDefParams,inKeyType,JConnDef(defName,fullName,"",many=false,distinct=false)) :: paramInfo.flatMap(_._2)
      case s ⇒ throw new Exception(s"${s.structure}")
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
        s"""  private class ${out.name}_Join(indexFactory: IndexFactory) extends Join(
           |    $toString,
           |    "${out.name}",
           |    collection.immutable.Seq(${params.map(p⇒s"${p.indexKeyName}(indexFactory)").mkString(",")}),
           |    ${out.indexKeyName}(indexFactory)
           |  ) {
           |    def joins(indexRawSeqSeq: IndexRawSeqSeq, diffIndexRawSeq: DiffIndexRawSeq): Result = {
           |      val iUtil = indexFactory.util
           |      val Seq(${params.map(p⇒s"${p.name}_diffIndex").mkString(",")}) = diffIndexRawSeq
           |      val invalidateKeySet = iUtil.invalidateKeySet(diffIndexRawSeq)
           |      ${params.map(p ⇒ if(p.distinct) s"""val ${p.name}_warn = "";""" else s"""val ${p.name}_warn = "${out.name} ${p.name} "+${p.indexKeyName}(indexFactory).valueClassName;""").mkString}
           |      for {
           |        indexRawSeqI <- indexRawSeqSeq
           |        (dir,indexRawSeq) = indexRawSeqI
           |        Seq(${params.map(p⇒s"${p.name}_index").mkString(",")}) = indexRawSeq
           |        id <- invalidateKeySet(indexRawSeq)
           |        ${seqParams.map(p⇒s"${p.name}_arg = iUtil.getValues(${p.name}_index,id,${p.name}_warn); ").mkString}
           |        ${seqParams.map(p⇒s"${p.name}_isChanged = iUtil.nonEmpty(${p.name}_diffIndex,id); ").mkString}
           |        ${eachParams.map(p⇒s"${p.name}_parts = iUtil.partition(${p.name}_index,${p.name}_diffIndex,id,${p.name}_warn); ").mkString}
           |        ${eachParams.map(p⇒s" ${p.name}_part <- ${p.name}_parts; (${p.name}_isChanged,${p.name}_items) = ${p.name}_part; ").mkString}
           |        pass <- if(
           |          ${if(eachParams.nonEmpty)"" else seqParams.map(p⇒s"${p.name}_arg.nonEmpty").mkString("("," || ",") && ")}
           |          (${params.map(p⇒s"${p.name}_isChanged").mkString(" || ")})
           |        ) iUtil.nonEmptySeq else Nil;
           |        ${eachParams.map(p⇒s"${p.name}_arg <- ${p.name}_items(); ").mkString}
           |        pair <- ${out.name}(id.asInstanceOf[${inKeyType.str}],${params.map(p⇒s"${p.name}_arg.asInstanceOf[${p.inValOuterType}]").mkString(",")})
           |      } yield {
           |        val (byKey,product) = pair
           |        iUtil.result(byKey,product,dir)
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
      case s ⇒ (" with ee.cone.c4assemble.CallerAssemble",s.mkString(s"  def subAssembles = List(",",",")\n"))
    }

    val paramNames = paramss.map(params⇒params.map{
      case param"..$mods $name: $tpeopt = $expropt" ⇒ if(expropt.isEmpty) Option(name) else None
    }.flatten.mkString("(",",",")")).mkString
    val paramNamesWithTypes = paramss.map(params⇒params.map{
      case param"..$mods $name: $tpeopt = $expropt" ⇒ if(expropt.isEmpty) Option(param"..${mods.collect{ case mod"valparam" ⇒ None case o ⇒ Option(o) }.flatten} $name: $tpeopt") else None
    }.flatten)
    val res = q"""class ${Type.Name(className)} [..$tparams] (...$paramNamesWithTypes)"""

    //cont.substring(0,className.pos.end) + "_Base" + cont.substring(className.pos.end) +
    (true,
      s"${res.syntax} extends ${baseClassName}$paramNames with ee.cone.c4assemble.CheckedAssemble$subAssembleWith " +
      s"{\n$statRules$joinImpl$dataDependencies$subAssembleDef}"
    )
  }}
}
