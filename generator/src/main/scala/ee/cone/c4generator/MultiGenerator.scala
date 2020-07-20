package ee.cone.c4generator
import scala.meta.Term.Name
import scala.meta._

object MultiGenerator extends Generator {
  case class MParam(
    pName: String, pType: String, pNameType: String, hasDefault: Boolean
  )
  def argsInner(list: List[List[Term.Param]]): List[List[MParam]] = for { params <- list } yield {
    for {
      p@param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params
    } yield MParam(name,tpe.syntax,param"${Name(name)}: ${Some(tpe)} = $expropt".syntax,expropt.nonEmpty)
  }


  def args(list: List[List[MParam]], name: String)(head: Boolean, withTypes: Boolean, withNames: Boolean): String = {
    val res = for { params <- if(head) List(list.head) else list.tail } yield {
      val group: List[String] = for {
        p <- params if head || !p.hasDefault
      } yield (withTypes, withNames, p.pType == name) match {
        case (true, true, true) => ""
        case (true, true, false) => p.pNameType
        case (false, true, true) => "this"
        case (false, true, false) => p.pName
        case (true, false, _) => p.pType
        case _ => throw new Exception
      }
      group.filter(_.nonEmpty).mkString("(",", ",")")
    }
    res.mkString(if (withNames) "" else "=>")
  }
  def get(parseContext: ParseContext): List[Generated] = {
    val res = getForStats(parseContext.stats)
    if(res.isEmpty) Nil else GeneratedImport("import ee.cone.c4di._") :: res
  }
  def getForStats(stats: List[Stat]): List[GeneratedCode] = for {
    cl <- Util.matchClass(stats)
    ann <- Util.singleSeq(cl.mods.collect{
      case mod"@c4multi(...$e)" => mod"@c4(...$e)".syntax
    })
  } yield {
    Util.assertFinal(cl)
    val name = s"${cl.name}Factory"
    val params = argsInner(cl.params)
    val par = args(params,name) _
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
    val arity = params.head.size
    val ext = if(typeParamNames.nonEmpty) ""
      else if(arity <= 3) s"extends C4Factory$arity[${params.head.map(p=>s"${p.pType},").mkString}${cl.name}]"
      else ""
    GeneratedCode(Seq(
      s"\n$ann final class $name${par(false,true,true)} $ext {",
      s"\n  def create$typeParams${par(true,true,true)} = ",
      s"\n    new ${cl.name}$typeParamNames${par(true,false,true)}${par(false,false,true)}",
      s"\n  def complete$typeParams(obj: ${par(false,true,false)} => ${cl.name}$typeParamNames): ${cl.name}$typeParamNames = ",
      s"\n    obj${par(false,false,true)} ",
      s"\n}"
    ).mkString)
  }
}
