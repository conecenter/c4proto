
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

  private def theTerm(arg: Any): Term.Name = Term.Name(s"the $arg")
  private def listedTerm(arg: Any): Term.Name = theTerm(s"List of $arg")
  private def theType(arg: Any): Type.Name = Type.Name(s"The $arg")

  def c4key: ArgPF = {
    case (t"$tpe[..$innerTypes] @c4key",None) ⇒
      val nArgs = innerTypes.map(i ⇒ q"(${Lit.String(s"$i")},${typeTree(i)})")
      val tpf = Type.Name(s"${tpe}Factory")
      val nm = theTerm(tpf)
      Option((q"$nm.forTypes(...${List(nArgs)})", Option(q"def $nm: $tpf")))
  }

  def prodLens: ArgPF = {
    case (_,Some(q"$o.of(...$args)")) ⇒
      val List(head :: tail) = args
      val q"_.$field" = head
      val nArgs = List(head :: q"value⇒model⇒model.copy($field=value)" :: Lit.String(s"$field") :: tail)
      Option((q"$o.ofSet(...$nArgs)",None))
  }

  def assembleArg: ArgPF = {
    case (t"$tpe ⇒ Assemble",None) ⇒
      val aTermName = Term.Name(s"Assemble of $tpe")
      val indexFactoryType = Type.Name("IndexFactory")
      val indexFactory = theTerm(indexFactoryType)
      Option((q"$aTermName($indexFactory)(_)",Option(q"def $indexFactory: $indexFactoryType")))
  }

  def defaultArgType: ArgPF = {
    case (tpe,Some(_)) ⇒ None
    case (t"()=>$t",None) ⇒
      val Some((term,stat)) = defaultArgType((t,None))
      Option((q"()=>$term",stat))
    case (tpe:Type.Name,None) ⇒
      val nm = theTerm(tpe)
      Option((nm,Option(q"def $nm: $tpe")))
    case (tpe@t"List[${Type.Name(ni)}]",None) ⇒
      val nm = listedTerm(ni)
      Option((nm,Option(q"def $nm: $tpe")))
    case (tpe@t"List[${Type.Name(ni)}[_]]",None) ⇒
      val nm = listedTerm(ni)
      Option((nm,Option(q"def $nm: $tpe")))
    /*
    case (tpe:Type.Apply,None) ⇒
      println(s"?type: $tpe ${tpe.structure}")
      Option((q"???",None))*/
  }

  def listedResult(ext: List[Init], tName: Any, concreteStatement: Term, needStms: List[Stat], mixType: Type.Name): Stat = {
    val init"$abstractType(...$_)" :: _ = ext
    val concreteTerm = theTerm(tName)
    val listTerm = listedTerm(abstractType)
    val statements =
      q"private lazy val ${Pat.Var(concreteTerm)} = $concreteStatement" ::
        q"override def $listTerm = $concreteTerm :: super.$listTerm " ::
        needStms
    val init = Init(theType(abstractType), Name(""), Nil) // q"".structure
    q"trait $mixType extends $init { ..$statements }"
  }

  def needStatements(tName: Type.Name, paramsList: List[List[Term.Param]]): (List[Stat],Term) = {
    val needsList = for {
      params ← paramsList.toList
    } yield for {
      param"..$mods $name: ${Some(tpe)} = $expropt" ← params
      r ← c4key.orElse(prodLens).orElse(assembleArg).orElse(defaultArgType)((tpe,expropt))
    } yield r
    val needParamsList = for { needs ← needsList }
      yield for { (param,_) ← needs } yield param
    val concreteStatement = q"${Term.Name(s"$tName")}(...$needParamsList)"
    val needStms = for {
      needs ← needsList
      (_,stmOpt) ← needs
      stm ← stmOpt
    } yield stm
    (needStms,concreteStatement)
  }

  def classComponent: PartialFunction[Tree,(Boolean,List[Stat])] = {
    //q"..$mods class $tname[..$tparams] ..$ctorMods (...$paramss) extends $template"
    case q"@c4component ..$mods class $tName[..$tParams](...$paramsList) extends ..$ext { ..$stats }" ⇒
      lazy val isAbstract = mods.collectFirst{ case mod"abstract" ⇒ true }.nonEmpty
      val isCase = mods.collectFirst{ case mod"case" ⇒ true }.nonEmpty
      val isListed = mods.collectFirst{ case mod"@listed" ⇒ true }.nonEmpty
      val mixType = theType(tName)
      val resStatement = (isAbstract,isCase,isListed) match {
        case (false,true,true) ⇒
          val (needStms,concreteStatement) = needStatements(tName,paramsList)
          listedResult(ext,tName,concreteStatement,needStms,mixType)
        case (false,true,false) ⇒
          val (needStms,concreteStatement) = needStatements(tName,paramsList)
          val init"${abstractType:Type}(...$_)" :: _ = ext
          val statements =
            q"lazy val ${Pat.Var(theTerm(abstractType))}: $abstractType = $concreteStatement" ::
              needStms
          q"trait $mixType { ..$statements }"
        case (true,false,true) ⇒
          val abstractType = Option(tParams.toList.map(_⇒Type.Placeholder(Type.Bounds(None, None))))
            .filter(_.nonEmpty).map(t⇒Type.Apply(tName,t))
            .getOrElse(tName)
          //println(t"List[_,_]".structure)

          val listTerm = listedTerm(tName)
          q"trait $mixType { def $listTerm: List[$abstractType] = Nil }"
        case a ⇒ throw new Exception(s"$tName unsupported mods: $a")
      }
      (true,resStatement::Nil)
    case q"@protocol object $objectName extends ..$ext { ..$stats }" ⇒
      val mixType = theType(objectName)
      (true,listedResult(ext,objectName,objectName,Nil,mixType)::Nil)
    case q"@assemble ..$mods class $tName[..$tParams](...$paramsList) extends ..$ext { ..$stats }" ⇒
      val aTypeName = Type.Name(s"Assemble of $tName")
      val aTermName = Term.Name(s"Assemble of $tName")
      val argTypeName = if(tParams.isEmpty) tName else Type.Apply(tName,tParams.map{ case tparam"$p <: $b" ⇒ Type.Name(s"$p") })
      val clStm = q"case class $aTypeName[..$tParams](indexFactory: IndexFactory)(ass: $argTypeName) extends Assemble { import ass._; ${AssembleGenerator(paramsList,stats)} }"
      val appTrait = if(tParams.isEmpty){
        val (needStms,concreteStatement) = needStatements(tName,paramsList)
        val mixType = theType(tName)
        val indexFactoryType = Type.Name("IndexFactory")
        val indexFactory = theTerm(indexFactoryType)
        listedResult(List(init"Assemble"),tName,q"$aTermName($indexFactory)($concreteStatement)",q"def $indexFactory: $indexFactoryType"::needStms,mixType) :: Nil
      } else Nil
      (true,clStm::appTrait)
  }

  def importForComponents: PartialFunction[Tree,(Boolean,List[Stat])] = {
    case q"import ..$s" ⇒ (false,q"import ..$s"::Nil)
  }

  lazy val componentCases: PartialFunction[Tree,(Boolean,List[Stat])] =
    importForComponents.orElse(classComponent)

  def genStatements: List[Stat] ⇒ Option[List[Stat]] = packageStatements ⇒
    Option(packageStatements.collect(componentCases).reverse.dropWhile(!_._1).reverseMap(_._2).flatten)
      .filter(_.nonEmpty)


  def genPackage(content: String): String = {
    val source = dialects.Scala211(content).parse[Source]
    val Parsed.Success(source"..$sourceStatements") = source
    val resStatments = for {
      q"package $n { ..$packageStatements }" ← sourceStatements.toList
      statements ← genStatements(packageStatements.toList)
    } yield q"package $n { ..$statements }"
    source"..$resStatments".syntax
    //FileWriter
  }


}


/*
features:
  repeat package/imports
  pass from app, no pass default
  single class | listed class | listed trait
  ()=>...
  ProdLens
  index access
todo: integrate,
todo: Getter,assemble,protocol
todo?: List[ExpressionsDumper[Unit]], object-apply single|listed
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


