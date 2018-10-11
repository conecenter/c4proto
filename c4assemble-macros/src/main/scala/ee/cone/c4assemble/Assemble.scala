package ee.cone.c4assemble

import scala.collection.immutable.Seq
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait RuleDef
case class JoinDef(params: Seq[AType], inKeyType: KVType, out: AType) extends RuleDef
case class AType(name: String, was: Boolean, key: KVType, value: KVType, strValuePre: String, many: Boolean, distinct: Boolean)

sealed trait KVType { def str: String }
case class SimpleKVType(name: String, str: String) extends KVType
case class GenericKVType(name: String, of: String, str: String) extends KVType
case class DoubleGenericKVType(name: String, of1: String, of2: String, str: String) extends KVType
case class NGenericKVType(name: String, ofN: Seq[String], str: String) extends KVType
object KVType {
  def unapply(t: Any): Option[KVType] = t match {
    case Some(e) ⇒ unapply(e)
    case Type.Name(n) ⇒ Option(SimpleKVType(n,n))
    case Type.Apply(Type.Name(n),Seq(Type.Name(of))) ⇒ Option(GenericKVType(n,of,s"$t"))
    case Type.Apply(Type.Name(n), Seq(Type.Name(of1), Type.Name(of2))) ⇒ Option(DoubleGenericKVType(n, of1, of2, s"$t"))
    case Type.Apply(Type.Name(n), types:Seq[Type.Name]) ⇒ Option(NGenericKVType(n, types.map(_.value), s"$t"))
  }
}

@compileTimeOnly("not expanded")
class assemble extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"class $className [..$tparams] (...$paramss) extends ..$ext { ..$stats }" = defn
    val rules: List[RuleDef] = stats.toList.flatMap {
      case q"type $tname = $tpe" ⇒ None
      case q"def ${Term.Name(defName)}(...${Seq(params)}): Values[(${KVType(outKeyType)},${KVType(outValType)})] = $expr" ⇒
        val param"$keyName: ${KVType(inKeyType)}" = params.head
        val joinDefParams = params.tail.map{
          case param"..$mods ${Term.Name(paramName)}: $manyT[${KVType(inValType)}]" ⇒
            val many = manyT match { case t"Values" ⇒ true case t"Each" ⇒ false }
            val paramRes = AType(paramName, was=false, inKeyType, inValType, s"$manyT", many, distinct=false)
            mods.foldLeft(paramRes){ (st,ann) ⇒
              ann match {
                case mod"@distinct" ⇒
                  st.copy(distinct=true)
                case mod"@was" ⇒
                  st.copy(was=true)
                case mod"@by[${KVType(annInKeyTypeV)}]" ⇒
                  st.copy(key=annInKeyTypeV)
                case Mod.Annot(
                  Term.Apply(
                    Term.ApplyType(
                      Ctor.Ref.Name("by"),
                      Seq(KVType(annInKeyTypeV))
                    ),
                    Nil
                  )
                ) ⇒
                  st.copy(key=annInKeyTypeV)
              }
            }
        }
        Option(JoinDef(joinDefParams,inKeyType,AType(defName,was=false,outKeyType,outValType,"",many=false,distinct=false)))
    }
    //val classArg =
    val classArgs = paramss.toList.flatten.collect{
      case param"${Term.Name(argName)}: Class[${Type.Name(typeName)}]" ⇒
        typeName -> argName
    }.toMap
    def classOfExpr(className: String) =
      classArgs.getOrElse(className,s"classOf[$className]") + ".getName"
    def simpleClassOfExpr(className: String) =
      classArgs.getOrElse(className,s"classOf[$className]") + ".getSimpleName"
    def classOfT(kvType: KVType) = kvType match {
      case SimpleKVType(n,_) ⇒ classOfExpr(n)
      case GenericKVType(n,of,_) ⇒ s"classOf[$n[_]].getName+'['+${classOfExpr(of)}+']'"
      case DoubleGenericKVType(n, of1, of2, _) ⇒ s"classOf[$n[_, _]].getName+'['+${classOfExpr(of1)}+','+${classOfExpr(of2)}+']'"
      case NGenericKVType(n, ofN, _) ⇒ s"classOf[$n[${ofN.map(_ ⇒ "_").mkString(", ")}]].getName+'['+${ofN.map(classOfExpr).mkString("+','+")}+']'"
    }
    /*
    def tp(kvType: KVType) = kvType match {
      case SimpleKVType(n) ⇒ n
      case GenericKVType(n,of) ⇒ s"$n[$of]"
    }*/
    def expr(specType: AType): String = {
      s"""indexFactory.util.joinKey(
         |${specType.was},
         |"${specType.key.str}",${classOfT(specType.key)},${classOfT(specType.value)}
         |)""".stripMargin
    }
    val joinImpl = rules.collect{
      case JoinDef(params,inKeyType,out) ⇒
        val (seqParams,eachParams) = params.partition(_.many)
        val fun = s"""
           |(indexRawSeqSeq,diffIndexRawSeq) => {
           |  val iUtil = indexFactory.util
           |  val Seq(${params.map(p⇒s"${p.name}_diffIndex").mkString(",")}) = diffIndexRawSeq
           |  val invalidateKeySet = iUtil.invalidateKeySet(diffIndexRawSeq)
           |  ${params.map(p ⇒ if(p.distinct) s"""val ${p.name}_warn = "";""" else s"""val ${p.name}_warn = "${out.name} ${p.name} "+${classOfT(p.value)};""").mkString}
           |  for {
           |    indexRawSeqI <- indexRawSeqSeq
           |    (dir,indexRawSeq) = indexRawSeqI
           |    Seq(${params.map(p⇒s"${p.name}_index").mkString(",")}) = indexRawSeq
           |    id <- invalidateKeySet(indexRawSeq)
           |    ${seqParams.map(p⇒s"${p.name}_arg = iUtil.getValues(${p.name}_index,id,${p.name}_warn); ").mkString}
           |    ${seqParams.map(p⇒s"${p.name}_isChanged = iUtil.nonEmpty(${p.name}_diffIndex,id); ").mkString}
           |    ${eachParams.map(p⇒s"${p.name}_parts = iUtil.partition(${p.name}_index,${p.name}_diffIndex,id,${p.name}_warn); ").mkString}
           |    ${eachParams.map(p⇒s" ${p.name}_part <- ${p.name}_parts; (${p.name}_isChanged,${p.name}_items) = ${p.name}_part; ").mkString}
           |    pass <- if(
           |      ${if(eachParams.nonEmpty)"" else seqParams.map(p⇒s"${p.name}_arg.nonEmpty").mkString("("," || ",") && ")}
           |      (${params.map(p⇒s"${p.name}_isChanged").mkString(" || ")})
           |    ) iUtil.nonEmptySeq else Nil;
           |    ${eachParams.map(p⇒s"${p.name}_arg <- ${p.name}_items(); ").mkString}
           |    pair <- ${out.name}(id.asInstanceOf[${inKeyType.str}],${params.map(p⇒s"${p.name}_arg.asInstanceOf[${p.strValuePre}[${p.value.str}]]").mkString(",")})
           |  } yield {
           |    val (byKey,product) = pair
           |    iUtil.result(byKey,product,dir)
           |  }
           |}
         """.stripMargin
        s"""
           |indexFactory.createJoinMapIndex(new ee.cone.c4assemble.Join(
           |  getClass.getName,
           |  "${out.name}",
           |  collection.immutable.Seq(${params.map(expr).mkString(",")}),
           |  ${expr(out)},
           |  $fun
           |))
         """.stripMargin
    }.mkString(s"override def dataDependencies = indexFactory ⇒ List(",",",")")

    val toString =
      if (tparams.isEmpty)
        s"""override val toString: String = "$className" + '@' + Integer.toHexString(hashCode())"""
      else
        s"""override val toString: String = "$className" + '@' + Integer.toHexString(hashCode()) + '['+ ${tparams.map(i ⇒ simpleClassOfExpr(i.name.toString())).mkString("+','+")} +']'"""

    val res = q"""
      class $className [..$tparams] (...$paramss) extends ..$ext {
        ..$stats;
        ${joinImpl.parse[Stat].get};

        ${toString.parse[Stat].get}
      }"""
    //println(res)
    res
  }
}
