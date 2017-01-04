package ee.cone.c4assemble

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait RuleDef
case class JoinDef(params: Seq[JoinType], in: JoinType, out: JoinType) extends RuleDef
case class JoinType(name: String, key: String, value: String)
case class SortDef(name: String, value: String) extends RuleDef

@compileTimeOnly("not expanded")
class assemble extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"class $objectName extends ..$ext { ..$stats }" = defn
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
            JoinType(paramName, annInKeyType, inValType)
        }
        Option(JoinDef(joinDefParams,JoinType("",inKeyType,"Object"),JoinType(defName,outKeyType,outValType)))
      case q"def ${Term.Name(defName)}: Iterable[${Type.Name(inType)}] ⇒ List[${Type.Name(outType)}] = $expr" if inType==outType ⇒
        Option(SortDef(defName,inType))
    }
    def expr(joinType: JoinType): String = {
      import joinType._
      s"""MacroJoinKey("$key",classOf[$key].getName,classOf[$value].getName)"""
    }
    val joinImpl = rules.collect{
      case JoinDef(params,in,out) ⇒
        s"""
           |indexFactory.createJoinMapIndex(new Join[${out.value},${in.key},${out.key}](
           |  Seq(${params.map(expr).mkString(",")}), ${expr(out)},
           |  (key,in) ⇒ in match {
           |    case Seq(${params.map(_ ⇒ "Nil").mkString(",")}) ⇒ Nil
           |    case Seq(${params.map(_.name).mkString(",")}) ⇒
           |      ${out.name}(key, ${params.map(param ⇒ s"${param.name}.asInstanceOf[Values[${param.value}]]").mkString(",")})
           |  }
           |), sorts.get(classOf[${out.value}]))
         """.stripMargin
    }.mkString(s"override def dataDependencies = (indexFactory,sorts) ⇒ List(",",",")")
    val sortImpl = rules.collect{
      case SortDef(name,value) ⇒ s".add(classOf[$value],$name)"
    }.mkString(s"override def sorts = sorts ⇒ sorts","","")

    val res = q"""
      object $objectName extends ..$ext {
        ..$stats;
        ${joinImpl.parse[Stat].get};
        ${sortImpl.parse[Stat].get};
      }"""
    //println(res)
    res
  }
}
