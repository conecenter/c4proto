package ee.cone.c4generator
import scala.meta.Term.Name
import scala.meta._

object MultiGenerator extends Generator {
  def args(list: List[List[Term.Param]])(head: Boolean, withTypes: Boolean): String = {
    val res = for { params <- if(head) List(list.head) else list.tail } yield {
      val group = for {
        p@param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params if head || expropt.isEmpty
      } yield if(withTypes) param"${Name(name)}: ${Some(tpe)} = $expropt".syntax else name
      group.mkString("(",", ",")")
    }
    res.mkString
  }
  def get(parseContext: ParseContext): List[GeneratedCode] = for {
    cl <- Util.matchClass(parseContext.stats)
    ann <- Util.singleSeq(cl.mods.collect{
      case mod"@c4multi(...$e)" => mod"@c4(...$e)".syntax
    })
  } yield {
    val par = args(cl.params) _
    val typeParams = cl.typeParams match { case Seq() => "" case p => p.mkString("[",", ","]") }
    GeneratedCode(Seq(
      s"\n$ann class ${cl.name}Factory${par(false,true)} {",
      s"\n  def create$typeParams${par(true,true)} = ",
      s"\n    new ${cl.name}$typeParams${par(true,false)}${par(false,false)}",
      s"\n}"
    ).mkString)
  }
}

