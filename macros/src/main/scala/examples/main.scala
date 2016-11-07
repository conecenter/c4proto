package examples


import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

@compileTimeOnly("@examples.Main not expanded")
class main extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $name { ..$stats }" = defn
    val main = q"def main(args: Array[String]): Unit = { ..$stats }"
    q"object $name { $main }"
  }
}

////

class Id(id: Int) extends StaticAnnotation
class scale(id: Int) extends StaticAnnotation

class ProtoMessage(id: Option[Int], name: String, props: Seq[Proto])

@compileTimeOnly("not expanded")
class schema extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $name { ..$stats }" = defn
    val res = stats.map{
      case q"..$mods trait ${Type.Name(traitName)} { ..$stats }" =>
        def idOpt(mods: Seq[Mod]): Option[Int] = mods match {
          case Seq() ⇒ None
          case Seq(mod"@Id(${Lit(id:Int)})") ⇒ Option(id)
        }
        val id: Option[Int] = idOpt(mods)
        stats.map{
          case q"..$mods def ${Term.Name(name)}: $tpe" ⇒
            val id = idOpt(mods)
            tpe match {
              case q"${Type.Name(name)}" ⇒
                //println(4,name)
              case t"Option[${Type.Name(name)}]" ⇒
                //println(3,name)
              case t"Option[BigDecimal] @scale(${Lit(scale:Int)})" ⇒
                println(6,scale)
              /*case expr ⇒
                println(5,expr.structure)*/
            }

        }
        //println(0,id)
        //println(0,traitName)

        //println(2,stats.structure)
        1
    }


    q"object $name { ..$stats }"
  }
}
