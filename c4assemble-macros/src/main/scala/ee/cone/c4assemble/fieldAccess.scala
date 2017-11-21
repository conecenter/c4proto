package ee.cone.c4assemble

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
//import org.scalameta.logger

@compileTimeOnly("not expanded")
class fieldAccess extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val nTree = defn.asInstanceOf[Tree].transform{
      case q"$o.of(...$args)" ⇒
        val List(head :: tail) = args
        val q"_.$field" = head
        val nArgs = List(head :: q"value⇒model⇒model.copy($field=value)" :: Lit(s"$field") :: tail)
        q"$o.ofSet(...$nArgs)"
    }
    nTree.asInstanceOf[Stat]
  }
}
