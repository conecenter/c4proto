
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

  def classComponent: PartialFunction[Tree,(Boolean,Stat)] = {
    case q"@c4component ..$mods class $cl(...$paramsList) extends ..$ext { ..$stats }" ⇒
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
      val needStms: List[Stat] = for {
        needs ← needsList
        (_,stmOpt) ← needs
        stm ← stmOpt
      } yield stm
      val isListed = mods.collectFirst{ case mod"@listed" ⇒ true }.nonEmpty
      val init"${Type.Name(abstractName)}(...$_)" :: Nil = ext
      val concreteTerm = Term.Name(s"the $cl")

      val concreteStatement = q"${Term.Name(s"$cl")}(...$needParamsList)"
      val resStatement = if(isListed) {
        val listName = s"the List[$abstractName]"
        val statements =
          q"private lazy val ${Pat.Var(concreteTerm)} = $concreteStatement" ::
          q"override def ${Term.Name(listName)}: ${Type.Name(listName)} = $concreteTerm :: super.${Term.Name(listName)} " ::
          needStms
        val init = Init(Type.Name(s"The $abstractName"), Name(""), Nil) // q"".structure
        q"trait ${Type.Name(s"The $cl")} extends $init { ..$statements }"
      } else {
        val statements =
          q"lazy val ${Pat.Var(Term.Name(s"the $abstractName"))}: ${Type.Name(s"the $abstractName")} = $concreteStatement" ::
          needStms
        q"trait ${Type.Name(s"The $cl")} { ..$statements }"
      }
      (true,resStatement)
  }

  def traitComponent: PartialFunction[Tree,(Boolean,Stat)] = {
    case q"@c4component ..$mods trait $cl extends ..$ext { ..$stats }" ⇒
      val Type.Name(abstractName) = cl
      val isListed = mods.collectFirst { case mod"@listed" ⇒ true }.nonEmpty
      val injectingStatement = if(isListed){
        val abstractTerm = Term.Name(s"the List[$abstractName]")
        val abstractType = Type.Name(s"the List[$abstractName]")
        q"def $abstractTerm: $abstractType = Nil"
      } else throw new Exception
      val stms = injectingStatement :: Nil
      (true, q"trait ${Type.Name(s"The $cl")} { ..$stms }")
  }

  def importForComponents: PartialFunction[Tree,(Boolean,Stat)] = {
    case q"import ..$s" ⇒ (false,q"import ..$s")
  }

  lazy val componentCases: PartialFunction[Tree,(Boolean,Stat)] =
    importForComponents.orElse(classComponent).orElse(traitComponent)

  def genStatements: List[Tree] ⇒ Option[List[Stat]] = packageStatements ⇒ {
    val pairs: List[(Boolean,Stat)] = packageStatements.collect(componentCases)
    val (reqiered,statements) = pairs.unzip
    if(reqiered.contains(true)) Option(statements) else None
  }

  def main(args: Array[String]): Unit = {
    val files = DirInfoImpl.deepFiles(Paths.get(".."))
      .filter(_.toString.endsWith(".scala"))
        .filterNot(_.toString.contains("-macros/"))
    files.foreach{ path ⇒
      val source = dialects.Scala211(path.toFile).parse[Source]
      val Parsed.Success(source"..$sourceStatements") = source
      val res = for {
        q"package $n { ..$packageStatements }" ← sourceStatements
        statements ← genStatements(packageStatements)
      } yield q"package $n { ..$statements }"


//q"..$mods trait $tname[..$tparams] extends $template"
      //val q"..$stms" = tree

          // q"class $className [..$tparams] (...$paramss) extends ..$ext { ..$stats }"
          //q"object $objectName extends ..$ext { ..$stats }"
          //q"..$mods case class ${Type.Name(messageName)} ( ..$params )"

      //val (_,stmList) = res.filter(_._1).unzip

      println(res)
    }
    //


  }
}
/*
features:
  repeat package/imports
  pass from app, no pass default
  single class | listed class | listed trait
todo: integrate, (ProdLens,Getter,ByPK,assemble,protocol)
problem:
  factory:
  - using (A,B)=>C is not good -- A & B are not named;
    so we need factory interface, it'll be in far file;
    so we need factory implementation;
    and we can skip not much;
  - we can use Inj[A] in place and replace by `Inj[A]`;
    so we skip args;
    but eithter loose debug with macro;
    or copy all code and compile 2 times
    !comment by macro, and generate 2nd

* */


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