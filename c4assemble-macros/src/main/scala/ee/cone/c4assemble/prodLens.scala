package ee.cone.c4assemble

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
//import org.scalameta.logger

@compileTimeOnly("not expanded")
class prodLens extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val nTree = defn.asInstanceOf[Tree].transform{
      case q"$o % _.$f" ⇒
        q"""$o.%(_.$f,value⇒_.copy($f=value),${Lit(s".$f")})"""
    }
    // println(nTree) //logger.elem(...)
    nTree.asInstanceOf[Stat]
  }
}

