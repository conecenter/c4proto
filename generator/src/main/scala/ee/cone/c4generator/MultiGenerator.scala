package ee.cone.c4generator
import scala.meta.Term.Name
import scala.meta._

object MultiGenerator extends Generator {
  def args(list: List[List[Term.Param]])(head: Boolean, withTypes: Boolean, withNames: Boolean): String = {
    val res = for { params <- if(head) List(list.head) else list.tail } yield {
      val group = for {
        p@param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params if head || expropt.isEmpty
      } yield (withTypes, withNames) match {
        case (true, true) => param"${Name(name)}: ${Some(tpe)} = $expropt".syntax
        case (false, true) => name
        case _ => tpe
      }
      group.mkString("(",", ",")")
    }
    res.mkString(if (withNames) "" else "=>")
  }
  def get(parseContext: ParseContext): List[GeneratedCode] =
    getForStats(parseContext.stats)
  def getForStats(stats: List[Stat]): List[GeneratedCode] = for {
    cl <- Util.matchClass(stats)
    ann <- Util.singleSeq(cl.mods.collect{
      case mod"@c4multi(...$e)" => mod"@c4(...$e)".syntax
    })
  } yield {
    val par = args(cl.params) _
    val typeParams = cl.typeParams match {
      case Seq() => ""
      case p => p.map{
        // case Type.Name(nm) => nm
        case tparam"..$_ $t" => t
        case tparam"..$_ $t <: $b" => tparam"..${Nil} $t <: $b"
        case a => throw new Exception(a.structure)
      }.mkString("[",", ","]")
    }
    val typeParamNames = cl.typeParams match {
      case Seq() => ""
      case p => p.map{
        // case Type.Name(nm) => nm
        case tparam"..$_ ${Type.Name(nm)} <: $_" => nm
        case a => throw new Exception(a.structure)
      }.mkString("[",", ","]")
    }
    GeneratedCode(Seq(
      s"\n$ann class ${cl.name}Factory${par(false,true,true)} {",
      s"\n  def create$typeParams${par(true,true,true)} = ",
      s"\n    new ${cl.name}$typeParamNames${par(true,false,true)}${par(false,false,true)}",
      s"\n  def complete$typeParams(obj: ${par(false,true,false)} => ${cl.name}$typeParams): ${cl.name}$typeParamNames = ",
      s"\n    obj${par(false,false,true)} ",
      s"\n}"
    ).mkString)
  }
}

