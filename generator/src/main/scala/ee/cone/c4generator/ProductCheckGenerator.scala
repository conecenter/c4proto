package ee.cone.c4generator

import scala.meta._

object ProductCheckGenerator extends Generator {
  private def clOf(t: String) = s"(classOf[$t]:Class[_]) :: "
  def canNotCheck(v: String): List[String] = s"/*canNotCheckProduct $v*/" :: Nil
  def getChecks(stat: Stat, oCtx: String): List[(String,String)] = stat.collect{
    case Defn.Class(cMods,Type.Name(name),tParam,ctor,code)
      if cMods.collect{ case mod"case" => true }.nonEmpty =>
      val tParamNames: Map[String,Option[Type]] = tParam.map{
        case Type.Param(_, Type.Name(n), Nil, Type.Bounds(None, b), Nil, _) =>
        n->b
      }.toMap
      val Ctor.Primary(_,_,firstListOfParams::_) = ctor
      val cChecks = firstListOfParams.flatMap{
        case pm@param"..$mods ${Term.Name(propName)}: $tpeopt = $expropt" =>
          def getTypes(tp: Type): List[String] = tp match {
            case Type.Repeated(t) => getTypes(t)
            case Type.Name(n) =>
              tParamNames.get(n) match {
                case None => clOf(n) :: Nil
                case Some(None) => canNotCheck(s"typeParamUnbound $pm")
                case Some(Some(Type.Name(m))) => clOf(m) :: Nil
              }
            case Type.Apply(t,ts) =>
              clOf(ts.map(_=>"_").mkString(s"$t[",",","]")) :: ts.flatMap(getTypes)
            case Type.With(_,Type.Name("Product")) => Nil
            case Type.With(t0,t1) => getTypes(t0) ++ getTypes(t1)
            case t: Type.Select => clOf(s"$t") :: Nil
            case t: Type.Placeholder =>
              t.bounds match {
                case Type.Bounds(None, Some(tb)) =>
                  getTypes(tb)
                case Type.Bounds(None, None) =>
                  canNotCheck(s"placeholder $pm")
              }
            case Type.Tuple(ts) => ts.flatMap(getTypes)
            case t: Type.Function => canNotCheck(s"function $pm")
            case t => throw new Exception(s"$stat -- " + t.structure)
          }




          if(mods.collect{ case mod"@c4ignoreProductCheck" => true }.nonEmpty) Nil
          else getTypes(tpeopt.asInstanceOf[Option[Type]].get)
      }.distinct
      if(cChecks.isEmpty) Nil else (s"check_$name ::: ",
        s"  def check_$name : List[Class[_]] = {\n" +
        (if(oCtx.isEmpty) "" else s"    import $oCtx._\n") +
        s"    ${cChecks.mkString}Nil\n" +
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
      GeneratedCode(
        s"object ProductCheck_$id {\n"+
        s"  def list: List[Class[_]] = ${checks.map(_._1).mkString}Nil\n"+
          checks.map(_._2).mkString +
        s"}\n"
      )::Nil
    }
  }
}
