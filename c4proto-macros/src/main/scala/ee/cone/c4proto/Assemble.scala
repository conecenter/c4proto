package ee.cone.c4proto

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

case class JoinDef(params: Seq[JoinType], in: JoinType, out: JoinType)
case class JoinType(name: String, key: String, value: String)

@compileTimeOnly("not expanded")
class assemble extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $objectName extends ..$ext { ..$stats }" = defn
    val rules: List[JoinDef] = stats.toList.flatMap {
      case q"type $tname = $tpe" ⇒ None
      case q"def ${Term.Name(defName)}(...${Seq(params)}): Values[(${Type.Name(outKeyType)},${Type.Name(outValType)})] = $expr" ⇒

        val param"$keyName: ${Some(Type.Name(inKeyType))}" = params.head
        //{}
        //println(tpe.getClass)
        //val inKeyType = tpe.toString()
        //params.head
        val joinDefParams = params.tail.map{
          case param"..$mods ${Term.Name(paramName)}: Values[${Type.Name(inValType)}]" ⇒
            val annInKeyType = mods match {
              case Seq() ⇒ inKeyType
              case Seq(mod"@by[${Type.Name(annInKeyType)}]") ⇒ annInKeyType
            }
            JoinType(paramName, annInKeyType, inValType)
        }
        Option(JoinDef(joinDefParams,JoinType("",inKeyType,"Object"),JoinType(defName,outKeyType,outValType)))
    }
    def expr(joinType: JoinType): String = {
      import joinType._
      s"""MacroJoinKey("$key",classOf[$key].getName,classOf[$value].getName)"""
    }
    val joinImpl = rules.map{ rule ⇒
      import rule._
      s"""
         |private class Join$$${out.name} extends JoinX[${out.value},${in.key},${out.key}] {
         |  val outputWorldKey : WorldKey[Index[${out.key},${out.value}]] =
         |    ${expr(out)}
         |  val inputWorldKeys : Seq[WorldKey[Index[${in.key},${in.value}]]] =
         |    Seq(${params.map(expr).mkString(",")})
         |  def joins(key: ${in.key}, in: Seq[Values[Object]]): Iterable[(${out.key},${out.value})] = in match {
         |    case Seq(${params.map(_⇒"Nil").mkString(",")}) ⇒ Nil
         |    case Seq(${params.map(_.name).mkString(",")}) ⇒
         |      ${out.name}(key, ${params.map(param⇒s"${param.name}.asInstanceOf[Values[${param.value}]]").mkString(",")})
         |  }
         |}
       """.stripMargin
      // def sort(values: Iterable[${out.value}]): List[${out.value}] = ???
    }
    val res = q"""
      object $objectName extends ..$ext {
        ..$stats;
        ..${joinImpl.map(_.parse[Stat].get)}
      }"""
    //println(res)
    res
  }
}