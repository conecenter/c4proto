package ee.cone.xsd

object OrigId {
  def apply(i: Long): String = "0x%04x".format(i)
}

object XMLCaseClassesGeneration {

  def getHeader(pckName: String, body: String): String = {
    s"""package $pckName
       |
       |import ee.cone.xml._
       |
       |$body
     """.stripMargin
  }

  def getField(name: String, fType: String): String = {
    s"$name:$fType"
  }

  def getCaseClass(name: String, fields: List[String]): String =
    s"""
       |case class $name(
       |${fields.mkString("  ", ",\n  ", "")}
       |)
     """.stripMargin

  def getParsers(schema: String, xsi: String, origs: List[OrigProp], pckName: String): String =
    (for {
      orig ← origs
    } yield {
      val fieldNames = orig.fields.map(_.name)
      s"""case object ${orig.name}XMLParser extends XMLBuilder[$pckName.${orig.name}]{
         #  def name: String = "${orig.name}"
         #
         #  def fromXML(in: xml.Node): $pckName.${orig.name} = {
         #    $pckName.${orig.name}(
         #${
        orig.fields.map(f ⇒
          s"""      (in \\ "${f.name}").text${
            f.fType match {
              case "String" ⇒ ""
              case "Long" ⇒ ".toLong"
              case "Int" ⇒ ".toInt"
              case a ⇒ throw new Exception("Can't find given type:" + a)
            }
          }"""
        ).mkString(",\n")
      }
         #    )
         #  }
         #
         #  def toXML(a: $pckName.${orig.name}): xml.Elem =
         #    <${orig.name} xsi:noNamespaceSchemaLocation="$schema" xmlns:xsi="$xsi">
         #${orig.fields.map(f ⇒ s"      <${f.name}>" + "{" + s"a.${f.name}" + s"}</${f.name}>").mkString("\n")}
         #    </${orig.name}>
         #}
       """.stripMargin('#')
    }).mkString("\n")

  def getApp(origs: List[OrigProp]): String = {
    s"""trait XMLCaseClassesApp extends XMLBuildersApp {
       |  override def XMLBuilders: List[XMLBuilder[_ <: Product]] = ${origs.map(o ⇒ o.name + "XMLParser").mkString("", " :: ", " :: ")}super.XMLBuilders
       |}
     """.stripMargin
  }

  def makeCaseClasses(pckName: String, origs: List[OrigProp], schema: String, xsi: String): String = {
    val lines =
      for {
        orig ← origs
      } yield {
        val fields =
          for {
            field ← orig.fields
          } yield {
            getField(field.name, field.fType)
          }
        getCaseClass(orig.name, fields)
      }
    getHeader(pckName, lines.mkString("")) + "\n" + getParsers(schema, xsi, origs, pckName) + "\n\n" + getApp(origs)
  }
}