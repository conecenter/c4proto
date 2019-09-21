package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta.Term.Name
import scala.meta._

object ComponentsGenerator extends Generator {
  //private def isC4Component = { case mod"@c4component" ⇒ true }
  def getApp(mods: Seq[Any]): String = mods.map{ case Lit(app:String) ⇒ app } match {
    case Seq(app) ⇒ app
    case Seq() ⇒ "DefApp"
  }
  def get: Get = { case (q"..$cMods class $tname[..$tparams] ..$ctorMods (...$paramsList) extends ..$ext { ..$stats }", fileName) ⇒
    val appsEx: Seq[Seq[Seq[Any]]] = cMods.collect{ case mod"@c4component(...$exprss)" ⇒ exprss }
    if(appsEx.isEmpty) Nil else {
      val app = getApp(appsEx.flatten.flatten)
      val Type.Name(tp) = tname
      val abstractTypes = ext.map{
        case init"${t@Type.Name(_)}(...$a)" ⇒
          val clArgs = a.flatten.collect{ case q"classOf[$a]" ⇒ a }
          if(clArgs.nonEmpty) Type.Apply(t,clArgs) else t
        case init"$t(...$_)" ⇒ t
      }
      val outs = abstractTypes.map(getTypeKey)
      val list = for{
        params ← paramsList.toList
      } yield for {
        param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" ← params
        r ← if(expropt.nonEmpty) None
        else Option((Option((tpe,name)),q"${Term.Name(name)}.asInstanceOf[$tpe]"))
      } yield r
      val args = for { args ← list } yield for { (_,a) ← args } yield a
      val caseSeq = for {(o,_) ← list.flatten; (_,a) ← o} yield a
      val depSeq = for { (o,_) ← list.flatten; (a,_) ← o } yield getTypeKey(a)
      val objName = Term.Name(s"${tp}Component")
      val concrete = q"new ${Type.Name(tp)}(...$args)".syntax
      List(GeneratedComponent(app,s"link$tp :: ",
        s"""\n  private def out$tp = """ +
        outs.map(s⇒s"\n    $s ::").mkString +
        s"""\n    Nil""" +
        s"""\n  private def in$tp = """ +
        depSeq.map(s⇒s"\n    $s ::").mkString +
        s"""\n    Nil""" +
        s"""\n  private def create$tp(args: scala.collection.immutable.Seq[Object]): $tp = {""" +
        s"""\n    val Seq(${caseSeq.mkString(",")}) = args;""" +
        s"""\n    $concrete""" +
        s"""\n  }""" +
        s"""\n  private lazy val link$tp = new Component(out$tp,in$tp,create$tp)"""
      ))
    }
  }
  def getTypeKey(t: Type): String = {
    t match {
      case t"$tpe[..$tpesnel]" =>
        val tArgs = tpesnel.map(_ ⇒ "_").mkString(", ")
        val args = tpesnel.flatMap{ case t"_" ⇒ Nil case t ⇒ List(getTypeKey(t)) }
        s"""TypeKey(classOf[$tpe[$tArgs]].getName, "$tpe", $args)"""
      case t"$tpe" ⇒
        s"""TypeKey(classOf[$tpe].getName, "$tpe", Nil)"""
    }
  }
  def join(objectName: String, content: String, comps: Seq[GeneratedComponent]): GeneratedCode = GeneratedCode(
    s"\nobject $objectName extends ee.cone.c4proto.AbstractComponents {" +
    "\n  import ee.cone.c4proto._" +
    content +
    comps.map(_.cContent).mkString +
    "\n  def components = " +
    comps.map(c ⇒ s"\n    ${c.name}").mkString +
    "\n    Nil" +
    "\n}"
  )
}
