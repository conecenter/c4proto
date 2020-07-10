package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta.Term.Name
import scala.meta._

sealed abstract class AbstractGeneratedComponent extends Product
case class GeneratedComponentAppLink(app: String, link: String) extends AbstractGeneratedComponent
case class GeneratedComponent(link: String, content: String) extends AbstractGeneratedComponent

object ComponentsGenerator extends Generator {
  case class InDep(typeKey: String, name: String, isTemplate: Boolean)
  def toContent(declaration: String, out: String, in: List[InDep], body: String): String = {
    val caseSeq = in.map(_.name)
      s"""\n$declaration extends ComponentCreator {""" +
      s"""\n  val out: TypeKey = $out""" +
      s"""\n  val in: Seq[TypeKey] = """ +
      in.map(_.typeKey).map(s=>s"\n    $s ::").mkString +
      s"""\n    Nil""" +
      s"""\n  def create(args: Seq[Object]): Seq[Object] = {""" +
        (if (caseSeq.size <= 22) s"""\n    val Seq(${caseSeq.mkString(",")}) = args;"""
        else
          s"""\n    println(\"WARN: component has more than 22 arguments\");""" +
          caseSeq.zipWithIndex.map { case (name, ind) => s"""    val $name = args.apply($ind)""" }.mkString("\n", "\n", "")) +
      s"""\n    $body""" +
      s"""\n  }""" +
      s"""\n}"""
  }

  val IsId = """(\w+)""".r

  def annArgToStr(arg: Any): Option[String] = arg match {
    case Lit(v:String) => Option(v)
    case args: Seq[_] => args.toList.flatMap(a=>annArgToStr(a).toList) match {
      case Seq() => None
      case Seq(r) => Option(r)
    }
  }
  def paramsToInDepList(paramsS: List[List[Term.Param]], replace: Option[String]): (List[InDep],String) = {
    val list: List[List[(InDep, String)]] = for{
      params <- paramsS.toList
    } yield for {
      param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params.toList
      r <- if(expropt.nonEmpty) None
      else {
        val key = getTypeKey(tpe,replace)
        Option((InDep(key.typeKey,name,key.isTemplate),s"$name=$name.asInstanceOf[$tpe]"))
      }
    } yield r
    val inSeq = for((o,_) <- list.flatten) yield o
    val args = for {
      args: List[(InDep,String)] <- list
      nArgs = for((_,a) <- args) yield a
    } yield nArgs.mkString("(",",",")")
    (inSeq,args.mkString)
  }

  def getComponent(cl: ParsedClass, parseContext: ParseContext): List[GeneratedComponent] = {
    Util.assertFinal(cl)
    val tp = cl.name
    val (inSeq,concrete) = paramsToInDepList(cl.params,None)
    assert(cl.typeParams.isEmpty,s"type params not supported for ${cl.name}")
    //
    val inSet = inSeq.map(_.typeKey).toSet
    val mainOut = getTypeKey(cl.nameNode)
    def checkLoop(out: String): String = {
      if(inSet(out)) println(s"isFinal: $out") // todo: die
      out
    }
    val mainAsArg = s"arg.asInstanceOf[$tp]"
    val mainAsDepList = List(InDep(mainOut,"arg",false))
    val abstractTypes: List[Type] = cl.ext.map{
      case init"${t@Type.Name(_)}(...$a)" =>
        val clArgs = a.flatten.collect{ case q"classOf[$a]" => a }
        if(clArgs.nonEmpty) Type.Apply(t,clArgs) else t
      case init"$t(...$_)" => t
    }.flatMap{
      case t@Type.Apply(Type.Name(n),_) => List(t,Type.Name(s"General$n"))
      case t: Type.Name => List(t)
    }

    def unSeq(typeOpt: Option[Type]): Type = typeOpt match {
      case Some(t"Seq[$tpe]") => tpe
      case Some(t) => Utils.parseError(t, parseContext, "provide needs out Seq")
    }
    val defOuts: List[GeneratedComponent] = cl.stats.flatMap{
      case q"..$cMods def ${Term.Name(defName)}[..$tParams](...$params): $tpeopt = $expr" =>
        if(cMods.collectFirst{ case mod"@provide" => true }.nonEmpty){
          tParams match {
            case Seq() =>
              assert(params.isEmpty)
              val outType = checkLoop(getTypeKey(unSeq(tpeopt)))
              val creatorName = s"${tp}_D$defName"
              val creatorContent = toContent(s"object $creatorName",outType,mainAsDepList,s"$mainAsArg.$defName")
              List(GeneratedComponent(creatorName, creatorContent))
            case Seq(tParamWithBound@tparam"${Type.Name(tParam)} <: $_") =>
              val (pInSeq,pArgs) = paramsToInDepList(params,Option(tParam))
              val outTypeR = getTypeKey(unSeq(tpeopt),Option(tParam))
              // println(s"AAA: $tParam : $outTypeR")
              val templateArgName = s"keyFor$tParam"
              val isFromOutput = outTypeR.isTemplate && outTypeR.typeKey != templateArgName
              val keyTemplate = if(isFromOutput) outTypeR.typeKey
                else pInSeq.find(_.isTemplate).get.typeKey
              val templateArgs = s"($templateArgName: TypeKey)"
              val outType = outTypeR.typeKey
              val creatorName = s"${tp}_D$defName"
              val typeKeyTemplate =
                if(isFromOutput) "FromOutputTypeKeyTemplate" else "FromInputTypeKeyTemplate"
              val creatorContent =
                s"\nobject $creatorName extends ComponentCreatorTemplate {" +
                s"\n  def getKey$templateArgs: TypeKeyTemplate = $typeKeyTemplate($keyTemplate)" +
                s"\n  def create(varTypeKey: TypeKey): $creatorName[_] = $creatorName(varTypeKey)" +
                "\n}" +
                toContent(
                  s"case class $creatorName[$tParamWithBound]$templateArgs",
                  outType, mainAsDepList ::: pInSeq,
                  s"$mainAsArg.$defName[$tParam]$pArgs"
                )
              List(GeneratedComponent(creatorName, creatorContent))
            case p => throw new Exception(s"Can't parse structure at ${parseContext.path} -- ${p.toString()} -- ${p.structure}")
          }
        } else {
          Nil
        }
      case _ =>
        //println(s"not def: $")
        Nil
    }
    val extOuts: List[GeneratedComponent] = if(defOuts.nonEmpty) Nil
      else abstractTypes.zipWithIndex.map{ case (t,i) =>
        val creatorName = s"${tp}_E$i"
        val outType = checkLoop(getTypeKey(t))
        val creatorContent = toContent(s"object $creatorName",outType,mainAsDepList,s"Seq($mainAsArg)")
        GeneratedComponent(creatorName, creatorContent)
      }
    // todo check: if(defOuts.nonEmpty && extOuts.nonEmpty) println(s"extOuts -- $tp -- $abstractTypes")
    val creatorName = s"${tp}Creator"
    val creatorContent = toContent(s"object $creatorName",mainOut,inSeq,s"Seq(new $tp$concrete)")
    val mOut: GeneratedComponent = GeneratedComponent(creatorName, creatorContent)
    mOut :: extOuts ::: defOuts
  }
  def get(parseContext: ParseContext): List[Generated] = {
    val components: List[AbstractGeneratedComponent] = for {
      cl <- Util.matchClass(parseContext.stats)
      (exprss,cl) <- Util.singleSeq(cl.mods.collect {
        case mod"@c4(...$exprss) " => (exprss, cl)
      })
      app = if(exprss.isEmpty) s"DefApp" else annArgToStr(exprss).get
      c <- getComponent(cl, parseContext)
      res <- new GeneratedComponentAppLink(app,c.link) :: c :: Nil
    } yield res
    if(components.isEmpty) Nil else wrapComponents(parseContext,components)
  }
  def wrapComponents(parseContext: ParseContext, components: List[AbstractGeneratedComponent]): List[Generated] = {
    val componentsId = s"${Util.pathToId(parseContext.path)}Components"
    val connects: List[Generated] = components.collect{ case c: GeneratedComponentAppLink => c }.groupMap(_.app)(_.link).toList.sortBy(_._1).flatMap{
      case (app,links) => List(
        GeneratedCode(
          s"\n  def forThe$app: List[Component] = " +
            links.map(c => s"\n    $c ::").mkString +
            "\n    Nil"
        ),
        GeneratedAppLink(parseContext.pkg,app,s"$componentsId.forThe$app")
      )
    }
    GeneratedImport("import ee.cone.c4di._") ::
    GeneratedImport("import scala.collection.immutable.Seq") ::
    components.collect{ case c: GeneratedComponent => GeneratedCode(c.content) } :::
    GeneratedCode(s"\nobject $componentsId {") :: connects ::: List(GeneratedCode("\n}"))
  }
  case class ResTypeKey(typeKey: String, isTemplate: Boolean)
  def getTypeKey(tt: Type, replace: Option[String]): ResTypeKey = {
    def trav(t: Type): ResTypeKey = t match {
      case t"_" => throw new Exception(s"$tt wildcard type disabled")
      case Type.Name(tp) if replace.contains(tp) => ResTypeKey(s"keyFor$tp",true)
      case Type.Name(_) | Type.Select(Term.Name(_), Type.Name(_)) =>
        ResTypeKey(s"""CreateTypeKey(classOf[$t], "$t", Nil)""",false)
      case t"$tpe[..$tpesnel]" =>
        val tArgs = tpesnel.map(_ => "_").mkString(", ")
        val inner = tpesnel.map(trav(_))
        val wasReplaced = inner.exists(_.isTemplate)
        val args = (inner.map(_.typeKey) ++ List("Nil")).mkString(" :: ")
        ResTypeKey(s"""CreateTypeKey(classOf[$tpe[$tArgs]], "$tpe", $args)""", wasReplaced)
      case t =>
        //println(s"trash type -- $tt -- ${tt.structure}")
        //ResTypeKey(s"""CreateTypeKey(classOf[$t], "$t", Nil)""",false)
        throw new Exception(s"$tt -- ${tt.structure}")
    }
    trav(tt)
  }
  def getTypeKey(t: Type): String = getTypeKey(t, None).typeKey
}
