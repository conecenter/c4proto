package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta.Term.Name
import scala.meta._

case class GeneratedComponent(appLink: Option[(String,String)], fixIn: Option[(String,String)], content: String)
object ComponentsGenerator extends Generator {
  def toContent(app: Option[String], name: String, out: String, nonFinalOut: Option[String], in: List[String], caseSeq: List[String], body: String, parent: String): GeneratedComponent =
    GeneratedComponent(app.map(a=>a->s"link$name ::: "), nonFinalOut.map(k=>out->s"$k.get"),
      s"""\n  private def out$name = $out""" +
      s"""\n  private def nonFinalOut$name = """ + nonFinalOut.getOrElse("None") +
      s"""\n  private def in$name = """ +
      in.map(s=>s"\n    $s ::").mkString +
      s"""\n    Nil""" +
      s"""\n  private def create$name(args: Seq[Object]) = {""" +
      s"""\n    val Seq(${caseSeq.mkString(",")}) = args;""" +
      s"""\n    $body""" +
      s"""\n  }""" +
      s"""\n  private lazy val link$name = new Component(out$name,nonFinalOut$name,in$name,create$name) :: $parent"""
    )

  def pkgNameToAppId(pkgName: String, add: String): Option[String] = {
    val IsId = """(\w+)""".r
    add match {
      case IsId(t) =>
        val nPkgName = """\.([a-z])""".r.replaceAllIn(s".$pkgName",m=>m.group(1).toUpperCase)
        Option(s"$nPkgName${t}App")
      case a => None
    }
  }
  def fileNameToComponentsId(fileName: String): String = {
    val SName = """.+/(\w+)\.scala""".r
    val SName(fName) = fileName
    s"${fName}Components"
  }
  def annArgToStr(arg: Any): Option[String] = arg match {
    case Lit(v:String) => Option(v)
    case args: Seq[_] => args.toList.flatMap(a=>annArgToStr(a).toList) match {
      case Seq() => None
      case Seq(r) => Option(r)
    }
  }
  def getComponent(cl: ParsedClass, getApp: Type=>Option[String]) = {
    val tp = cl.name
    val list = for{
      params <- cl.params.toList
    } yield for {
      param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" <- params.toList
      r <- if(expropt.nonEmpty) None
      else Option((Option((tpe,name)),q"${Term.Name(name)}.asInstanceOf[$tpe]"))
    } yield r
    val args = for { args <- list } yield for { (_,a) <- args } yield a
    val caseSeq = for {(o,_) <- list.flatten; (_,a) <- o} yield a
    val depSeq = for { (o,_) <- list.flatten; (a,_) <- o } yield getTypeKey(a)
    val objName = Term.Name(s"${tp}Component")
    val concrete = q"Seq(new ${Type.Name(tp)}(...$args))".syntax
    assert(cl.typeParams.isEmpty)
    //
    val inSet = depSeq.toSet
    val mainOut = getTypeKey(cl.nameNode)
    def outToContent(name: String, t: Type, body: String=>String): GeneratedComponent = {
      val out = getTypeKey(t)
      val isFinal = inSet(out)
      val fixIn = if(isFinal) Option(s"""Option($out).map(o=>o.copy(alias=s"NonFinal#"+o.alias))""") else None
      toContent(getApp(t),name,out,fixIn,List(mainOut),List("arg"),body(s"arg.asInstanceOf[$tp]"),s"link$tp")
    }
    val abstractTypes: List[Type] = cl.ext.map{
      case init"${t@Type.Name(_)}(...$a)" =>
        val clArgs = a.flatten.collect{ case q"classOf[$a]" => a }
        if(clArgs.nonEmpty) Type.Apply(t,clArgs) else t
      case init"$t(...$_)" => t
    }
    val extOuts: List[GeneratedComponent] = abstractTypes.zipWithIndex.map{ case (t,i) =>
      outToContent(s"${tp}_E$i",t,a=>s"Seq($a)")
    }
    val defOuts: List[GeneratedComponent] = cl.stats.flatMap{
      case q"..$cMods def ${Term.Name(defName)}(...$params): $tpeopt = $expr" =>
        if(cMods.collectFirst{ case mod"@provide" => true }.isEmpty){
          Nil
        } else {
          assert(params.isEmpty)
          val Some(t"Seq[$t]") = tpeopt
          List(outToContent(s"${tp}_D$defName",t,a=>s"$a.$defName"))
        }
      case _ =>
        //println(s"not def: $")
        Nil
    }
    val outs: List[GeneratedComponent] = extOuts ::: defOuts
    val fixIn = outs.flatMap(_.fixIn).toMap
    val inSeq = depSeq.map(k=>fixIn.getOrElse(k,k))
    toContent(None,tp,mainOut,None,inSeq,caseSeq,concrete,"Nil") :: outs
  }
  def get(parseContext: ParseContext): List[Generated] = {
    val components: List[GeneratedComponent] = for {
      cl <- Util.matchClass(parseContext.stats)
      getApp <- Util.singleSeq(cl.mods.collect{
        case mod"@c4(...$exprss)" => (t:Type) => if(exprss.nonEmpty) annArgToStr(exprss) else pkgNameToAppId(parseContext.pkg,t.toString)
      })
      res <- getComponent(cl,getApp)
    } yield res
    if(components.isEmpty) Nil else wrapComponents(parseContext,components)
  }
  def wrapComponents(parseContext: ParseContext, components: List[GeneratedComponent]): List[Generated] = {
    val componentsId = fileNameToComponentsId(parseContext.path)
    val connects: List[Generated] = components.flatMap(_.appLink).groupMap(_._1)(_._2).toList.sortBy(_._1).flatMap{
      case (app,constr) => List(
        GeneratedCode(
          s"\n  def forThe$app = " +
            constr.map(c => s"\n    $c").mkString +
            "\n    Nil"
        ),
        GeneratedAppLink(parseContext.pkg,app,s"$componentsId.forThe$app")
      )
    }
    GeneratedCode(
      s"\nobject $componentsId {" +
        "\n  import ee.cone.c4proto._" +
        "\n  import scala.collection.immutable.Seq" +
        components.map(_.content).mkString
    ) :: connects ::: List(GeneratedCode("\n}"))
  }
  def getTypeKey(t: Type): String = t match {
    case t"$tpe[..$tpesnel]" =>
      val tArgs = tpesnel.map(_ => "_").mkString(", ")
      val args = tpesnel.flatMap{ case t"_" => Nil case t => List(getTypeKey(t)) }
      s"""TypeKey(classOf[$tpe[$tArgs]].getName, "$tpe", $args)"""
    case t"$tpe" =>
      s"""TypeKey(classOf[$tpe].getName, "$tpe", Nil)"""
  }
}
