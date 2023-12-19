package ee.cone.c4generator

import scala.meta._

object TimeJoinParamTransformer extends JoinParamTransformer {
  def transform(parseContext: ParseContext): Term.Param => Option[Term.Param] = {
    case param@param"@time(${Term.Name(timeName)}) $name: $tpeopt = $expropt" =>
      if (timeName == "CurrentTime") Utils.parseError(param, parseContext, "Wrong CurrentTimeName")
      if (expropt.asInstanceOf[Option[Term]].nonEmpty) Utils.parseError(param, parseContext, "@time params can't have default value")
      val newType = tpeopt match {
        case None => Utils.parseError(param, parseContext, "No type provided")
        case Some(t"Each[T_Time]") => tpeopt
        case Some(t"Values[T_Time]") => tpeopt
        case _ => Utils.parseError(param, parseContext, "Invalid type for time, use T_Time")
      }
      val paramName: Name = name
      Some(param"@byEq[SrcId@ns(${Term.Name(timeName)}.srcId)](${Term.Name(timeName)}.srcId) $paramName: $newType")
    case _ => None
  }
}

class TimeGenerator(protocolGenerator: ProtocolGenerator, metaGenerator: MetaGenerator) extends Generator {

  def get(parseContext: ParseContext): List[Generated] =
    parseContext.stats.collect {
      case q"@c4time(..$exprs) case object $name extends CurrentTime($refresh)" =>
        val id :: rest = exprs.asInstanceOf[List[Stat]].map(_.syntax)
        getProtocolImports ::: getGeneralConfig(name.value, rest)
    }.flatten

  def getProtocolImports: List[Generated] =
    GeneratedImport("import ee.cone.c4actor.Types.SrcId") ::
      GeneratedImport("import ee.cone.c4actor.time.T_Time") :: Nil

  def getGeneralConfig(name: String, traits: List[String]): List[Generated] =
    GeneratedImport("import ee.cone.c4di.c4") ::
      GeneratedImport("import ee.cone.c4actor.time._") ::
      GeneratedImport("import ee.cone.c4actor.Types.SrcId") ::
      GeneratedImport("import ee.cone.c4actor.GetByPK") ::
      GeneratedImport("import ee.cone.c4actor.AssembledContext") ::
      GeneratedCode(
        s"""@c4${if (traits.isEmpty) "" else traits.mkString("(", ", ", ")")} final class ${name}CurrTimeConfig(val timeGetter: GetByPK[T_Time]) extends TimeGetter(${name}) with CurrTimeConfig {
           |  def ofA(context: AssembledContext): Option[T_Time] = timeGetter.ofA(context).get(currentTime.srcId)
           |}""".stripMargin
      ) :: Nil

}
