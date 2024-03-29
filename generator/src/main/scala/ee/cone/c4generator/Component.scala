package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta.Term.Name
import scala.meta._

sealed abstract class AbstractGeneratedComponent extends Product
case class GeneratedComponentAppLink(app: String, link: String) extends AbstractGeneratedComponent
case class GeneratedComponent(typeStr: String, link: String, fixIn: Option[(String,String)], content: String) extends AbstractGeneratedComponent
case class GeneratedComponentImport(content: String) extends AbstractGeneratedComponent

object ComponentsGenerator extends Generator {
  def toContent(typeStr: String, name: String, out: String, nonFinalOut: Option[String], in: List[String], caseSeq: List[String], body: String): GeneratedComponent =
    GeneratedComponent(typeStr,s"link$name",nonFinalOut.map(k=>out->s"$k.get"),
      s"""\n  private def out$name = $out""" +
      s"""\n  private def nonFinalOut$name = """ + nonFinalOut.getOrElse("None") +
      s"""\n  private def in$name = """ +
      in.map(s=>s"\n    $s ::").mkString +
      s"""\n    Nil""" +
      s"""\n  private def create$name(args: Seq[Object]) = {""" +
        (if (caseSeq.size <= 22) s"""\n    val Seq(${caseSeq.mkString(",")}) = args;"""
        else
          s"""\n    println(\"WARN: $name component has more than 22 arguments\");""" +
          caseSeq.zipWithIndex.map { case (name, ind) => s"""    val $name = args.apply($ind)""" }.mkString("\n", "\n", "")) +
      s"""\n    $body""" +
      s"""\n  }""" +
      s"""\n  lazy val link$name: Component = new Component(out$name,nonFinalOut$name,in$name,create$name)"""
    )

  val IsId = """(\w+)""".r

  def annArgToStr(arg: Any): Option[String] = arg match {
    case Lit(v:String) => Option(v)
    case args: Seq[_] => args.toList.flatMap(a=>annArgToStr(a).toList) match {
      case Seq() => None
      case Seq(r) => Option(r)
    }
  }
  def getComponent(cl: ParsedClass, parseContext: ParseContext, app: String): List[AbstractGeneratedComponent] = {
    Util.assertFinal(cl)
    val tp = cl.name
    val list = for{
      params <- cl.params.toList
    } yield for {
      param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params.toList
      r <- if(expropt.nonEmpty) None
      else Option((Option((tpe,name)),s"$name=$name.asInstanceOf[$tpe]"))
    } yield r
    val args = for { args <- list } yield for { (_,a) <- args } yield a
    val caseSeq = for {(o,_) <- list.flatten; (_,a) <- o} yield a
    val depSeq = for { (o,_) <- list.flatten; (a,_) <- o } yield getTypeKey(a,None)
    val objName = Term.Name(s"${tp}Component")
    val concrete = s"Seq(new $tp${args.map(a=>s"(${a.mkString(",")})").mkString})"
    assert(cl.typeParams.isEmpty,s"type params not supported for ${cl.name}")
    //
    val inSet = depSeq.toSet
    val mainOut = getTypeKey(cl.nameNode,None)
    def outToContent(name: String, t: Type, body: String=>String, wildCard: Option[String]): GeneratedComponent = {
      val out = getTypeKey(t,wildCard)
      val isFinal = inSet(out)
      val fixIn = if(isFinal) Option(s"""Option($out).map(o=>o.copy(alias=s"NonFinal#"+o.alias))""") else None
      toContent(t.toString,name,out,fixIn,List(mainOut),List("arg"),body(s"arg.asInstanceOf[$tp]"))
    }
    val abstractTypes: List[Type] = cl.ext.map{
      case init"${t@Type.Name(_)}(...$a)" =>
        val clArgs = a.flatten.collect{ case q"classOf[$a]" => a }
        if(clArgs.nonEmpty) Type.Apply(t,clArgs) else t
      case init"$t(...$_)" => t
    }
    val generalTypePairs: Seq[(String,String)] = abstractTypes.flatMap{
      case t@Type.Apply(Type.Name(n),_) => List((s"$n",s"General$n"))
      case t: Type.Name => Nil
    }
    val generalTypes = generalTypePairs.map{ case (_,n) => Type.Name(n) }
    val extOuts: List[GeneratedComponent] = (abstractTypes++generalTypes).zipWithIndex.map{ case (t,i) =>
      outToContent(s"${tp}_E$i",t,a=>s"Seq($a)",None)
    }
    val defOuts: List[GeneratedComponent] = cl.stats.flatMap{
      case q"..$cMods def ${Term.Name(defName)}(...$params): $tpeopt = $expr" =>
        if(cMods.collectFirst{ case mod"@provide" => true }.isEmpty){
          Nil
        } else {
          assert(params.isEmpty)
          val t = tpeopt match {
            case Some(t"Seq[$tpe]") => tpe
            case Some(t) => Utils.parseError(t, parseContext, "@component")
          }
          val wildCard = t match {
            case t"ComponentFactory[$_]" => Option("")
            case _ => None
          }
          List(outToContent(s"${tp}_D$defName",t,a=>s"$a.$defName",wildCard))
        }
      case _ =>
        //println(s"not def: $")
        Nil
    }
    val outs: List[GeneratedComponent] = extOuts ::: defOuts
    val fixIn = outs.flatMap(_.fixIn).toMap
    val inSeq = depSeq.map(k=>fixIn.getOrElse(k,k))
    val components =  toContent("",tp,mainOut,None,inSeq,caseSeq,concrete) :: outs
    Util.importFriends(parseContext, generalTypePairs).toList.map(GeneratedComponentImport) ++
      components.map(c => GeneratedComponentAppLink(app,c.link)) ++ components
  }
  def get(parseContext: ParseContext): List[Generated] = {
    val components: List[AbstractGeneratedComponent] = for {
      cl <- Util.matchClass(parseContext.stats)
      (exprss,cl) <- Util.singleSeq(cl.mods.collect {
        case mod"@c4(...$exprss) " => (exprss, cl)
      })
      app = if(exprss.isEmpty) s"DefApp" else annArgToStr(exprss).get
      res <- getComponent(cl, parseContext, app)
    } yield res
    if(components.isEmpty) Nil else wrapComponents(parseContext,components)
  }
  def wrapComponents(parseContext: ParseContext, components: List[AbstractGeneratedComponent]): List[Generated] = {
    val componentsId = s"${Util.pathToId(parseContext.path)}Components"
    val connects: List[Generated] = components.collect{ case c: GeneratedComponentAppLink => c }.groupMap(_.app)(_.link).toList.sortBy(_._1).flatMap{
      case (app,links) => List(
        GeneratedCode(
          s"\n  def forThe$app = " +
            links.map(c => s"\n    $c ::").mkString +
            "\n    Nil"
        ),
        GeneratedAppLink(parseContext.pkg,app,s"$componentsId.forThe$app")
      )
    }
    GeneratedCode(
      s"\nobject $componentsId {" +
        components.collect{ case c: GeneratedComponentImport => s"\n  ${c.content}" }.mkString +
        "\n  import ee.cone.c4di._" +
        "\n  import scala.collection.immutable.Seq" +
        components.collect{ case c: GeneratedComponent => c.content }.mkString
    ) :: connects ::: List(GeneratedCode("\n}"))
  }
  def getTypeKey(t: Type, wildCard: Option[String]): String = t match {
    case t"$tpe[..$tpesnel]" =>
      val tArgs = tpesnel.map(_ => "_").mkString(", ")
      val args = tpesnel.map{
        case t"_" => wildCard.getOrElse(throw new Exception(s"$t wildcard type disabled"))
        case c => s"${getTypeKey(c,wildCard)} :: "
      }.mkString
      s"""CreateTypeKey(classOf[$tpe[$tArgs]], "$tpe", ${args}Nil)"""
    case t"$tpe" =>
      s"""CreateTypeKey(classOf[$tpe], "$tpe", Nil)"""
  }
}
