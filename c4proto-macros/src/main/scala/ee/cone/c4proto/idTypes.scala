package ee.cone.c4proto
/*
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

@compileTimeOnly("not expanded")
class idTypes extends StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val nTree = defn.asInstanceOf[Tree].transform{
      case e@q"..$mods def $n: $tpeopt = $expr" ⇒
        val mod"@Id($id)" :: Nil = mods
        val t"$cl[..$tArgs]" = tpeopt.get
        val tArg :: Nil = tArgs
        q"..$mods lazy val ${Pat.Var.Term(n)}: $tpeopt = ($expr).idType($id,classOf[$tArg])"
    }
    nTree.asInstanceOf[Stat]
  }
}
*/