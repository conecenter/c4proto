package ee.cone.c4assemble

import scala.collection.immutable.Seq
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait RuleDef
case class JoinDef(params: Seq[AType], in: AType, out: AType) extends RuleDef
case class AType(name: String, key: String, value: String)

@compileTimeOnly("not expanded")
class assemble extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"class $className [..$tparams] (...$paramss) extends ..$ext { ..$stats }" = defn
    val rules: List[RuleDef] = stats.toList.flatMap {
      case q"type $tname = $tpe" ⇒ None
      case q"def ${Term.Name(defName)}(...${Seq(params)}): Values[(${Type.Name(outKeyType)},${Type.Name(outValType)})] = $expr" ⇒
        val param"$keyName: ${Some(Type.Name(inKeyType))}" = params.head
        val joinDefParams = params.tail.map{
          case param"..$mods ${Term.Name(paramName)}: Values[${Type.Name(inValType)}]" ⇒
            val annInKeyType = mods match {
              case Nil ⇒ inKeyType
              case mod"@by[${Type.Name(annInKeyType)}]" :: Nil ⇒ annInKeyType
            }
            AType(paramName, annInKeyType, inValType)
        }
        Option(JoinDef(joinDefParams,AType("",inKeyType,"Product"),AType(defName,outKeyType,outValType)))
    }
    //val classArg =
    val classArgs = paramss.toList.flatten.collect{
      case param"${Term.Name(argName)}: Class[${Type.Name(typeName)}]" ⇒
        typeName -> argName
    }.toMap
    def classOfExpr(className: String) =
      classArgs.getOrElse(className,s"classOf[$className]") + ".getName"
    def expr(genType: AType, specType: AType): String = {
      s"""ee.cone.c4assemble.JoinKey[${genType.key},${genType.value}]("${specType.key}",${classOfExpr(specType.key)},${classOfExpr(specType.value)})"""
    }
    val joinImpl = rules.collect{
      case JoinDef(params,in,out) ⇒
        s"""
           |indexFactory.createJoinMapIndex[${in.value},${out.value},${in.key},${out.key}](new ee.cone.c4assemble.Join[${in.value},${out.value},${in.key},${out.key}](
           |  collection.immutable.Seq(${params.map(expr(in,_)).mkString(",")}), ${expr(out,out)},
           |  (key,in) ⇒ in match {
           |    case Seq(${params.map(_ ⇒ "Nil").mkString(",")}) ⇒ Nil
           |    case Seq(${params.map(_.name).mkString(",")}) ⇒
           |      ${out.name}(key, ${params.map(param ⇒ s"${param.name}.asInstanceOf[Values[${param.value}]]").mkString(",")})
           |  }
           |))
         """.stripMargin
    }.mkString(s"override def dataDependencies = indexFactory ⇒ List(",",",")")

    val res = q"""
      class $className [..$tparams] (...$paramss) extends ..$ext {
        ..$stats;
        ${joinImpl.parse[Stat].get};
      }"""
    //println(res)
    res
  }
}
