
import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8

import scala.meta._

object Generator {
  type ArgPF = PartialFunction[(Type,Option[Term]),Option[(Term,Option[Stat])]]

  def typeTree: PartialFunction[Type,Term] = {
    case tp@Type.Name(nm) ⇒ q"classOf[$tp].getName"
    case t"$tp[..$innerTypes]" ⇒
      val inner = innerTypes.map(typeTree).reduce((a,b)⇒q"$a + $b")
      q"classOf[$tp].getName + '[' + $inner + ']'"
  }

  def c4key: ArgPF = {
    case (t"$tpe[..$innerTypes] @c4key",None) ⇒
      val nArgs = innerTypes.map(i ⇒ q"(${Lit.String(s"$i")},${typeTree(i)})")
      Option((q"${Term.Name(s"the ${tpe}Factory")}.forTypes(...${List(nArgs)})",None))
  }

  def prodLens: ArgPF = {
    case (_,Some(q"$o.of(...$args)")) ⇒
      val List(head :: tail) = args
      val q"_.$field" = head
      val nArgs = List(head :: q"value⇒model⇒model.copy($field=value)" :: Lit.String(s"$field") :: tail)
      Option((q"$o.ofSet(...$nArgs)",None))
  }

  def defaultArgType: ArgPF = {
    case (tpe,Some(_)) ⇒ None
    case (tpe,None) ⇒
      val nm = Term.Name(s"the $tpe")
      Option((nm,Option(q"def $nm: $tpe")))
  }


  def classComponent: PartialFunction[Tree,(Boolean,Stat)] = {
    //q"..$mods class $tname[..$tparams] ..$ctorMods (...$paramss) extends $template"
    case q"@c4component ..$mods class $tName[..$tParams](...$paramsList) extends ..$ext { ..$stats }" ⇒
      lazy val needsList = for {
        params ← paramsList.toList
      } yield for {
        param"..$mods $name: ${Some(tpe)} = $expropt" ← params
        r ← c4key.orElse(prodLens).orElse(defaultArgType)((tpe,expropt))
      } yield r
      lazy val needParamsList = for { needs ← needsList }
        yield for { (param,_) ← needs } yield param
      lazy val concreteStatement = q"${Term.Name(s"$tName")}(...$needParamsList)"
      lazy val needStms = for {
        needs ← needsList
        (_,stmOpt) ← needs
        stm ← stmOpt
      } yield stm
      lazy val isAbstract = mods.collectFirst{ case mod"abstract" ⇒ true }.nonEmpty
      val isCase = mods.collectFirst{ case mod"case" ⇒ true }.nonEmpty
      val isListed = mods.collectFirst{ case mod"@listed" ⇒ true }.nonEmpty
      val mixType = Type.Name(s"The $tName")
      val resStatement = (isAbstract,isCase,isListed) match {
        case (false,true,true) ⇒
          val init"$abstractType(...$_)" :: Nil = ext
          val concreteTerm = Term.Name(s"the $tName")
          val listTerm = Term.Name(s"the List[$abstractType]")
          val statements =
            q"private lazy val ${Pat.Var(concreteTerm)} = $concreteStatement" ::
              q"override def $listTerm = $concreteTerm :: super.$listTerm " ::
              needStms
          val init = Init(Type.Name(s"The $abstractType"), Name(""), Nil) // q"".structure
          q"trait $mixType extends $init { ..$statements }"
        case (false,true,false) ⇒
          val init"${abstractType:Type}(...$_)" :: Nil = ext
          val statements =
            q"lazy val ${Pat.Var(Term.Name(s"the $abstractType"))}: $abstractType = $concreteStatement" ::
              needStms
          q"trait $mixType { ..$statements }"
        case (true,false,true) ⇒
          val abstractType = Option(tParams.map(_⇒Type.Placeholder(Type.Bounds(None, None))))
            .filter(_.nonEmpty).map(t⇒Type.Apply(tName,t))
            .getOrElse(tName)
          //println(t"List[_,_]".structure)

          val listTerm = Term.Name(s"the List[$abstractType]")
          q"trait $mixType { def $listTerm: List[$abstractType] = Nil }"
        case _ ⇒ throw new Exception
      }
      (true,resStatement)
  }

  def importForComponents: PartialFunction[Tree,(Boolean,Stat)] = {
    case q"import ..$s" ⇒ (false,q"import ..$s")
  }

  lazy val componentCases: PartialFunction[Tree,(Boolean,Stat)] =
    importForComponents.orElse(classComponent)

  def genStatements: List[Stat] ⇒ Option[List[Stat]] = packageStatements ⇒
    Option(packageStatements.collect(componentCases).reverse.dropWhile(!_._1).reverseMap(_._2))
      .filter(_.nonEmpty)


  def genPackage(content: String): List[Pkg] = {
    val source = dialects.Scala211(content).parse[Source]
    val Parsed.Success(source"..$sourceStatements") = source
    for {
      q"package $n { ..$packageStatements }" ← sourceStatements.toList
      statements ← genStatements(packageStatements.toList)
    } yield q"package $n { ..$statements }"
  }


}


/*
features:
  repeat package/imports
  pass from app, no pass default
  single class | listed class | listed trait
  ProdLens
  index access
todo: integrate, (Getter,assemble,protocol)
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


