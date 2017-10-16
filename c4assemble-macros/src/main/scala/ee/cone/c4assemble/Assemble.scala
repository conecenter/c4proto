package ee.cone.c4assemble

import scala.collection.immutable.Seq
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait RuleDef
case class JoinDef(params: Seq[AType], in: AType, out: AType) extends RuleDef
case class AType(name: String, key: KVType, value: KVType)

sealed trait KVType
case class SimpleKVType(name: String) extends KVType
case class GenericKVType(name: String, of: String) extends KVType
object KVType {
  def unapply(n: Any): Option[KVType] = n match {
    case Some(e) ⇒ unapply(e)
    case Type.Name(n) ⇒ Option(SimpleKVType(n))
    case Type.Apply(Type.Name(n),Seq(Type.Name(of))) ⇒ Option(GenericKVType(n,of))
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
          case param"..$mods ${Term.Name(paramName)}: Values[${KVType(inValType)}]" ⇒
            val annInKeyType = mods match {
              case Nil ⇒ inKeyType
              case mod"@by[${KVType(annInKeyType)}]" :: Nil ⇒ annInKeyType
            }
            AType(paramName, annInKeyType, inValType)
        }
        Option(JoinDef(joinDefParams,AType("",inKeyType,SimpleKVType("Product")),AType(defName,outKeyType,outValType)))
    }
    //val classArg =
    val classArgs = paramss.toList.flatten.collect{
      case param"${Term.Name(argName)}: Class[${Type.Name(typeName)}]" ⇒
        typeName -> argName
    }.toMap
    def classOfExpr(className: String) =
      classArgs.getOrElse(className,s"classOf[$className]") + ".getName"
    def classOfT(kvType: KVType) = kvType match {
      case SimpleKVType(n) ⇒ classOfExpr(n)
      case GenericKVType(n,of) ⇒ s"classOf[$n[_]].getName+'['+${classOfExpr(of)}+']'"
    }
    def tp(kvType: KVType) = kvType match {
      case SimpleKVType(n) ⇒ n
      case GenericKVType(n,of) ⇒ s"$n[$of]"
    }
    def expr(genType: AType, specType: AType): String = {
      s"""ee.cone.c4assemble.JoinKey[${tp(genType.key)},${tp(genType.value)}]("${tp(specType.key)}",${classOfT(specType.key)},${classOfT(specType.value)})"""
    }
    val joinImpl = rules.collect{
      case JoinDef(params,in,out) ⇒
        val tParams = s"${tp(in.value)},${tp(out.value)},${tp(in.key)},${tp(out.key)}"
        s"""
           |indexFactory.createJoinMapIndex[$tParams](new ee.cone.c4assemble.Join[$tParams](
           |  "${out.name}",
           |  collection.immutable.Seq(${params.map(expr(in,_)).mkString(",")}),
           |  ${expr(out,out)},
           |  (key,in) ⇒ in match {
           |    case Seq(${params.map(_ ⇒ "Nil").mkString(",")}) ⇒ Nil
           |    case Seq(${params.map(_.name).mkString(",")}) ⇒
           |      ${out.name}(key, ${params.map(param ⇒ s"${param.name}.asInstanceOf[Values[${tp(param.value)}]]").mkString(",")})
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
