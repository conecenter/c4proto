package ee.cone.c4generator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
//import org.scalameta.logger

object FieldAccessGenerator extends Generator {
  def get: Get = { case (Defn.Object(Seq(mod"@fieldAccess"),baseObjectNameNode@Term.Name(baseObjectName),code), _) ⇒ Util.unBase(baseObjectName,baseObjectNameNode.pos.end){objectName⇒
    //case q"@fieldAccess object $name $code" ⇒
    //  println(s"=-=$code")
    //Util.comment(code)(cont) +
    val nCode = code.transform{
      case q"$o.of(...$args)" ⇒
        val List(head :: tail) = args
        val q"_.$field" = head
        val nArgs = List(head :: q"value⇒model⇒model.copy($field=value)" :: Lit.String(s"$field") :: tail)
        q"$o.ofSet(...$nArgs)"
    }

    List(GeneratedCode(Defn.Object(Nil,Term.Name(objectName),nCode.asInstanceOf[Template]).syntax))
  }}
}

