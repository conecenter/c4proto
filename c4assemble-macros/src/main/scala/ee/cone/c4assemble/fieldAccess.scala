package ee.cone.c4assemble

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
//import org.scalameta.logger

/**** overall:
modelAccess = modelAccessFactory.ofModel(model)
fieldAccess = modelAccess.ofField(of,set,postfix)
receiver.ofField(fieldAccess)
****/

@compileTimeOnly("not expanded")
class fieldAccess extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    def access(product: String) = Term.Name(s"$product$$c4access")
    val iTree = defn.asInstanceOf[Tree].transform{
      case q"$receiver.binds($product.$field)" ⇒
        q"$receiver.bindsAccess(${access(s"$product")}.map(_.ofField(_.$field)))"
      case q"$factory.conducts(${Term.Name(product)})" ⇒
        q"val ${Pat.Var.Term(access(product))} = $factory.ofModel(${Term.Name(product)})"
    }
    val nTree = iTree.transform{
      case q"$o.ofField(_.$f)" ⇒
        q"""$o.ofField(model⇒model.$f,value⇒model⇒model.copy($f=value),${Lit(s".$f")})"""
    }
    // println(nTree) //logger.elem(...)
    nTree.asInstanceOf[Stat]
  }
}

