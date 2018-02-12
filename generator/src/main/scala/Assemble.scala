
import scala.collection.immutable.Seq
import scala.meta._

sealed trait RuleDef
case class JoinDef(params: Seq[AType], in: AType, out: AType) extends RuleDef
case class AType(name: String, was: Boolean, key: KVType, value: KVType)

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


object AssembleGenerator {
  def apply(paramsList: List[List[Term.Param]], stats: List[Stat]): Stat = {
    val rules: List[RuleDef] = stats.toList.flatMap {
      case q"type $tname = $tpe" ⇒ None
      case q"def ${Term.Name(defName)}(...${Seq(params)}): Values[(${KVType(outKeyType)},${KVType(outValType)})] = $expr" ⇒
        val param"$keyName: ${KVType(inKeyType)}" = params.head
        val joinDefParams = params.tail.map{
          case param"..$mods ${Term.Name(paramName)}: Values[${KVType(inValType)}]" ⇒
            val (was,annInKeyType) = mods.foldLeft((false,inKeyType)){ (st,ann) ⇒
              ann match {
                case mod"@was" ⇒
                  st.copy(_1=true)
                case mod"@by[${KVType(annInKeyTypeV)}]" ⇒
                  st.copy(_2=annInKeyTypeV)
                /*case Mod.Annot(
                  Term.Apply(
                    Term.ApplyType(
                      Ctor.Ref.Name("by"),
                      Seq(KVType(annInKeyTypeV))
                    ),
                    Nil
                  )
                ) ⇒
                  st.copy(_2=annInKeyTypeV)*/
              }
            }
            AType(paramName, was, annInKeyType, inValType)
        }
        Option(JoinDef(joinDefParams,AType("",was=false,inKeyType,SimpleKVType("Product")),AType(defName,was=false,outKeyType,outValType)))
    }
    //val classArg =
    val classArgs = paramsList.toList.flatten.collect{
      case param"..$mods ${Term.Name(argName)}: Class[${Type.Name(typeName)}]" ⇒
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
      s"""ee.cone.c4assemble.JoinKey[${tp(genType.key)},${tp(genType.value)}](${specType.was},"${tp(specType.key)}",${classOfT(specType.key)},${classOfT(specType.value)})"""
    }
    val joinImpl = rules.collect{
      case JoinDef(params,in,out) ⇒
        val tParams = s"${tp(in.value)},${tp(out.value)},${tp(in.key)},${tp(out.key)}"
        s"""
           |indexFactory.createJoinMapIndex[$tParams](new ee.cone.c4assemble.Join[$tParams](
           |  getClass.getName,
           |  "${out.name}",
           |  collection.immutable.Seq(${params.map(expr(in,_)).mkString(",")}),
           |  ${expr(out,out)},
           |  (key,in) ⇒ in match {
           |    case Seq(${params.map(_ ⇒ "Nil").mkString(",")}) ⇒ Nil
           |    case Seq(${params.map(_.name).mkString(",")}) ⇒
           |      ass.${out.name}(key, ${params.map(param ⇒ s"${param.name}.asInstanceOf[Values[${tp(param.value)}]]").mkString(",")})
           |  }
           |))
         """.stripMargin
    }.mkString(s"def dataDependencies = List(",",",")")
    joinImpl.parse[Stat].get
  }
}
