package ee.cone.c4generator

import scala.meta._

object TimeGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] =
    parseContext.stats.collect {
      case q"@c4time(..$exprs) case object $name extends CurrentTime($refresh)" =>
        val id :: rest = exprs.asInstanceOf[List[Stat]].map(_.syntax)
        val protocol = getProtocol(name.value, id, rest)
        getProtocolImports ::: (GeneratedCode(protocol) ::
          ProtocolGenerator.get(
            new ParseContext(
              protocol.parse[Stat].get :: Nil,
              parseContext.path, parseContext.pkg
            )
          )) ::: getGeneralConfig(name.value, refresh.syntax, rest)
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

  def getGeneralConfig(name: String, refreshRate: String, traits: List[String]): List[Generated] =
    GeneratedImport("import ee.cone.c4di.c4") ::
      GeneratedImport("import ee.cone.c4actor.time._") ::
      GeneratedImport("import ee.cone.c4actor.Types.SrcId") ::
      GeneratedCode(
        s"""@c4${if (traits.isEmpty) "" else traits.mkString("(", ", ", ")")} class ${name}CurrTimeConfig extends CurrTimeConfig[T_${name}] {
           |  lazy val currentTime: CurrentTime = ${name}
           |  lazy val cl: Class[T_${name}] = classOf[T_${name}]
           |  lazy val default: T_${name} = T_${name}(currentTime.srcId, currentTime.refreshRateSeconds)
           |  lazy val set: Long => T_${name} => T_${name} = v => _.copy(millis = v)
           |}""".stripMargin
      ) :: Nil

}
