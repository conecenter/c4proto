package ee.cone.c4generator

import scala.meta._

object ProductCheckGenerator extends Generator {
  private def clOf(t: String): String = s"(classOf[$t]:Class[_])"
  def tupStr(l: Seq[String]): String = l.mkString("(",",",")")
  def notIgnored(mods: Seq[Mod]): Boolean =
    mods.collect{ case mod"@c4ignoreProductCheck" => true }.isEmpty
  def getChecks(stat: Stat, oCtx: String): List[(String,String)] = stat.collect{
    case Defn.Class(cMods,Type.Name(name),tParam,ctor,code)
      if cMods.collect{ case mod"case" => true }.nonEmpty && notIgnored(cMods) =>
      val tParamNames: Map[String,Option[Type]] = tParam.map{
        case Type.Param(_, Type.Name(n), Nil, Type.Bounds(None, b), Nil, _) =>
        n->b
      }.toMap
      val Ctor.Primary(_,_,firstListOfParams::_) = ctor
      val cChecks = firstListOfParams.collect{
        case pm@param"..$mods ${Term.Name(propName)}: $tpeopt = $expropt" if notIgnored(mods) =>
          def getType(tp: Type): String = tp match {
            case Type.Repeated(t) => getType(t)
            case Type.Name(n) =>
              clOf(tParamNames.get(n) match {
                case None => n
                case Some(None) => "Any"
                case Some(Some(Type.Name(m))) => m
              })
            case Type.Apply(t,ts) =>
              tupStr(clOf(ts.map(_=>"_").mkString(s"$t[",",","]")) :: ts.map(getType))
            case Type.With(_,Type.Name("Product")) => clOf("Product")
            case Type.With(t0,t1) => tupStr(Seq("\"OR\"",getType(t0),getType(t1)))
            case t: Type.Select => clOf(s"$t")
            case t: Type.Placeholder =>
              t.bounds match {
                case Type.Bounds(None, Some(tb)) => getType(tb)
                case Type.Bounds(None, None) => clOf("Any")
              }
            case Type.Tuple(ts) => tupStr("\"AND\"" :: ts.map(getType))
            case _: Type.Function => clOf(s"Function[_,_]")
            case t => throw new Exception(s"$stat -- " + t.structure)
          }
          getType(tpeopt.asInstanceOf[Option[Type]].get)
      }.distinct
      if(cChecks.isEmpty) Nil else (s"check_$name ::: ",
        s"  def check_$name : List[_] = {\n" +
        (if(oCtx.isEmpty) "" else s"    import $oCtx._\n") +
        s"    List${tupStr(cChecks)}\n" +
        "  }\n"
      )::Nil
  }.flatten

  def get(parseContext: ParseContext): List[Generated] = {
    val checks: List[(String,String)] = parseContext.stats.flatMap {
      case o: Defn.Object => o.templ.stats.flatMap(getChecks(_,o.name.value))
      case s => getChecks(s,"")
    }
    if(checks.isEmpty) Nil else {
      val id = Util.pathToId(parseContext.path)
      GeneratedCode("import ee.cone.c4di._") :: GeneratedCode(
        s"@c4 final class ProductCheck_$id extends ProductCheck {\n"+
        s"  def list: List[_] = ${checks.map(_._1).mkString}Nil\n"+
          checks.map(_._2).mkString +
        s"}\n"
      )::Nil
    }
  }
}
