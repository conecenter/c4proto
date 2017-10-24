
import java.nio.file.{Files, Path, Paths}
import scala.meta._

/*
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
*/


object Main {
  def main(args: Array[String]): Unit = {
    val files = DirInfoImpl.deepFiles(Paths.get(".."))
      .filter(_.toString.endsWith(".scala"))
        .filterNot(_.toString.contains("-macros/"))
    files.foreach{ path ⇒
      val source = dialects.Scala211(path.toFile).parse[Source]
      val Parsed.Success(source"..$stms") = source

      //val q"..$stms" = tree
      val res = stms.collect{
        case stm@q"import ..$_" ⇒
          println("I",stm)
          (false,stm)
          // q"class $className [..$tparams] (...$paramss) extends ..$ext { ..$stats }"
          //q"object $objectName extends ..$ext { ..$stats }"
          //q"..$mods case class ${Type.Name(messageName)} ( ..$params )"
        case q"@appComponent ..$mods case class $cl(...$paramsList) extends ..$ext { ..$stats }" ⇒

          val needsList = for {
            params ← paramsList
          } yield for {
            param"..$mods $name: $tpeopt = $expropt" ← params
            tpe ← tpeopt
            r ← (expropt match {
              //case (_,Some(q"ProdLens.of()"))
              case Some(_) ⇒ None
              case None ⇒
                val nm = Term.Name(s"the $tpe")
                Option((nm,Option(q"def $nm: $tpe")))
            })
          } yield r

          val needParamsList = for { needs ← needsList }
            yield for { (param,_) ← needs } yield param
          val needStms = for {
            needs ← needsList
            (_,stmOpt) ← needs
            stm ← stmOpt
          } yield stm


          val stms = q"private lazy val ${Pat.Var(Term.Name(s"the $cl"))} = ${Term.Name(s"$cl")}(...$needParamsList)" :: needStms


            //case param"${Term.Name(argName)}: Class[${Type.Name(typeName)}]" ⇒

          (true,q"trait ${Type.Name(s"The $cl")} { ..$stms }")


      }
      val (_,stmList) = res.filter(_._1).unzip

      println(stmList)
    }
    //integrate, ProdLens, listed/single, listed trait


  }
}

object FinallyClose {
  def apply[A<:AutoCloseable,R](o: A)(f: A⇒R): R = try f(o) finally o.close()
  def apply[A,R](close: A⇒Unit)(o: A)(f: A⇒R): R = try f(o) finally close(o)
}


import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait DirInfo {
  def deepFiles(path: Path): List[Path]
}

object DirInfoImpl extends DirInfo {
  def sortedList(dir: Path): List[Path] =
    FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted
  def deepFiles(path: Path): List[Path] = {
    if(!Files.exists(path)) Nil
    else if(Files.isDirectory(path)) sortedList(path).flatMap(deepFiles)
    else List(path) //Files.isRegularFile(path)
  }
}