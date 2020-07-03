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
    val list = for{
      params <- paramsS.toList
    } yield for {
      param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params.toList
      r <- if(expropt.nonEmpty) None
      else {
        val key = getTypeKey(tpe,replace)
        Option((InDep(key.key,name,key.wasReplaced),s"$name=$name.asInstanceOf[$tpe]"))
      }
    } yield r
    val inSeq = for((o,_) <- list.flatten) yield o
    val args = (for {
      args <- list
    } yield s"(${(for { (_,a) <- args } yield a).mkString(",")})").mkString
    (inSeq,args)
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
    val extOuts: List[GeneratedComponent] = abstractTypes.zipWithIndex.map{ case (t,i) =>
      val creatorName = s"${tp}_E$i"
      val outType = checkLoop(getTypeKey(t))
      val creatorContent = toContent(s"object $creatorName",outType,mainAsDepList,s"Seq($mainAsArg)")
      GeneratedComponent(creatorName, creatorContent)
    }
    def unSeq(typeOpt: Option[Type]): Type = typeOpt match {
      case Some(t"Seq[$tpe]") => tpe
      case Some(t) => Utils.parseError(t, parseContext, "provive needs out Seq")
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
            case Seq(Type.Name(tParam)) =>
              val (pInSeq,pArgs) = paramsToInDepList(params,Option(tParam))
              val outTypeR = getTypeKey(unSeq(tpeopt),Option(tParam))
              val isFromOutput = outTypeR.wasReplaced && outTypeR.key != tParam
              val keyTemplates = if(isFromOutput) List(outTypeR.key) else pInSeq.filter(_.isTemplate).map(_.key)



//              val provideForType = cMods.collect{ case mod"@provideFor[$t[_]]" => t } match {
//                case Seq(t) => getTypeKey(t, None)
//              }


              val outType = outTypeR.key
              val creatorName = s"${tp}_D$defName"
              val creatorContent =
                "\nobject $creatorName extends ComponentCreatorTemplate {" +
                  "\n  def create(varTypeKey: TypeKey): $creatorName = new $creatorName(varTypeKey)" +
                  "\n}" +
                  toContent(s"class $creatorName(keyFor$tParam: TypeKey)", outType, mainAsDepList ::: pInSeq, s"$mainAsArg.$defName$pArgs")
              List(GeneratedComponent(creatorName, creatorContent))

          }




        //} else if(cMods.collectFirst{ case mod"@provideFor[$t]" => true }.nonEmpty){

        } else {
          Nil
        }
      case _ =>
        //println(s"not def: $")
        Nil
    }
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
          s"\n  def forThe$app: List[ComponentCreator] = " +
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
  case class ResTypeKey(key: String, wasReplaced: Boolean)
  def getTypeKey(t: Type, replace: Option[String]): ResTypeKey = t match {
    case t"_" => throw new Exception(s"$t wildcard type disabled")
    case Type.Name(tp) if replace.contains(tp) => ResTypeKey(s"keyFor$tp",true)
    case Type.Name(tp) =>
      ResTypeKey(s"""CreateTypeKey(classOf[$tp], "$tp", Nil)""",false)
    case t"$tpe[..$tpesnel]" =>
      val tArgs = tpesnel.map(_ => "_").mkString(", ")
      val inner = tpesnel.map(getTypeKey(_,replace))
      val wasReplaced = inner.exists(_.wasReplaced)
      val args = (inner.map(_.key) ++ List("Nil")).mkString(" :: ")
      ResTypeKey(s"""CreateTypeKey(classOf[$tpe[$tArgs]], "$tpe", $args)""", wasReplaced)
  }
  def getTypeKey(t: Type): String = getTypeKey(t, None).key
}
