package ee.cone.c4generator

import scala.meta._

object TimeJoinParamTransformer extends JoinParamTransformer {
  def transform(parseContext: ParseContext): Term.Param => Option[Term.Param] = {
    case param@param"@time(${Term.Name(timeName)}) $name: $tpeopt = $expropt" =>
      if (timeName == "CurrentTime") Utils.parseError(param, parseContext, "Wrong CurrentTimeName")
      if (expropt.asInstanceOf[Option[Term]].nonEmpty) Utils.parseError(param, parseContext, "@time params can't have default value")
      val newType = tpeopt match {
        case None => Utils.parseError(param, parseContext, "No type provided")
        case Some(t"Each[T_Time]") => t"Each[${Type.Name(s"T_$timeName")}]"
        case Some(t"Values[T_Time]") => t"Values[${Type.Name(s"T_$timeName")}]"
        case _ => Utils.parseError(param, parseContext, "Invalid type for time, use T_Time")
      }
      val paramName: Name = name
      Some(param"@byEq[SrcId](${Term.Name(timeName)}.srcId) $paramName: $newType")
    case _ => None
  }
}

class TimeGenerator(protocolGenerator: ProtocolGenerator) extends Generator {

  def get(parseContext: ParseContext): List[Generated] =
    parseContext.stats.collect {
      case q"@c4time(..$exprs) case object $name extends CurrentTime($refresh)" =>
        val id :: rest = exprs.asInstanceOf[List[Stat]].map(_.syntax)
        val protocol = getProtocol(name.value, id, rest)
        getProtocolImports ::: (GeneratedCode(protocol) ::
          protocolGenerator.get(
            new ParseContext(
              protocol.parse[Stat].get :: Nil,
              parseContext.path, parseContext.pkg
            )
          )) ::: getGeneralConfig(name.value, rest)
    }.flatten

  def getProtocolImports: List[Generated] =
    GeneratedImport("import ee.cone.c4actor.Types.SrcId") ::
      GeneratedImport("import ee.cone.c4actor.time.T_Time") ::
      GeneratedImport("import ee.cone.c4proto._") :: Nil

  def getProtocol(name: String, id: String, traits: List[String]): String =
    s"""@protocol${if (traits.isEmpty) "" else traits.mkString("(", ", ", ")")} object Proto${name}Base {
       |
       |  @Id(${id}) case class T_${name}(
       |    @Id(0x0001) srcId: SrcId,
       |    @Id(0x0002) millis: Long
       |  ) extends T_Time
       |
       |}""".stripMargin

  def getGeneralConfig(name: String, traits: List[String]): List[Generated] =
    GeneratedImport("import ee.cone.c4di.c4") ::
      GeneratedImport("import ee.cone.c4actor.time._") ::
      GeneratedImport("import ee.cone.c4actor.Types.SrcId") ::
      GeneratedImport("import ee.cone.c4actor.GetByPK") ::
      GeneratedImport("import ee.cone.c4actor.AssembledContext") ::
      GeneratedImport("import ee.cone.c4actor.AssembledContext") ::
      GeneratedCode(
        s"""@c4${if (traits.isEmpty) "" else traits.mkString("(", ", ", ")")} final class ${name}CurrTimeConfig(val timeGetter: GetByPK[T_${name}]) extends TimeGetter(${name}) with CurrTimeConfig[T_${name}] {
           |  lazy val cl: Class[T_${name}] = classOf[T_${name}]
           |  lazy val default: T_${name} = T_${name}(currentTime.srcId, currentTime.refreshRateSeconds)
           |  lazy val set: Long => T_${name} => T_${name} = v => _.copy(millis = v)
           |  def ofA(context: AssembledContext): Option[T_Time] = timeGetter.ofA(context).get(currentTime.srcId)
           |}""".stripMargin
      ) :: Nil

}
