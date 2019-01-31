package ee.cone.c4generator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
//import org.scalameta.logger

object FieldAccessGenerator extends Generator {
  def get: Get = {
    case code@Defn.Object(Seq(mod"@fieldAccess"),_,_) ⇒ cont ⇒
    //case q"@fieldAccess object $name $code" ⇒
    //  println(s"=-=$code")
    Util.comment(code)(cont) + "\n\n" + code.transform{
      case q"$o.of(...$args)" ⇒
        val List(head :: tail) = args
        val q"_.$field" = head
        val nArgs = List(head :: q"value⇒model⇒model.copy($field=value)" :: Lit.String(s"$field") :: tail)
        q"$o.ofSet(...$nArgs)"
    }.syntax
  }
}

