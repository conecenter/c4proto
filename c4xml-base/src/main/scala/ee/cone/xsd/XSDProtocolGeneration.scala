package ee.cone.xsd

object Hex2 {
  def apply(i: Long): String = "0x%04x".format(i)
}

object XMLProtocolGeneration {
  val offset = 40960

  def getId(outer: Int, inner: Int): String = {
    Hex2(inner + outer * 24 + offset)
  }

  def getHeader(pckName: String, name: String, body: String, origNames: List[String]): String = {
    s"""package $pckName
       |
       |import ee.cone.c4actor._
       |import ee.cone.c4proto._
       |import ee.cone.xml._
       |import $pckName.$name.{${origNames.mkString(", ")}}
       |
       |@protocol(XMLCat) object $name extends Protocol{
       |$body
       |}
     """.stripMargin
  }

  def getField(id: String, name: String, fType: String): String = {
    s"@Id($id) $name:$fType"
  }

  def getOrig(id: String, name: String, fields: List[String]): String =
    s"""
       |  @Id($id) case class $name(
       |${fields.mkString("    ", ",\n    ", "")}
       |  )
     """.stripMargin

  def getParsers(schema: String, xsi: String, origs: List[OrigProp]): String =
    (for {
      orig ← origs
    } yield {
      val fieldNames = orig.fields.map(_.name)
      s"""case object ${orig.name}XMLParser extends XMLBuilder[${orig.name}]{
         #  def name: String = "${orig.name}"
         #
         #  def fromXML(in: xml.Node): ${orig.name} = {
         #    ${orig.name}(
         #${
        orig.fields.map(f ⇒ s"""      (in \\ "${f.name}").text${
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
         #  def toXML(a: ${orig.name}): xml.Elem =
         #    <${orig.name} xsi:noNamespaceSchemaLocation="$schema" xmlns:xsi="$xsi">
         #${orig.fields.map(f ⇒ s"      <${f.name}>" + "{" + s"a.${f.name}" + s"}</${f.name}>").mkString("\n")}
         #    </${orig.name}>
         #}
       """.stripMargin('#')
    }).mkString("\n")

  def getApp(protocolName: String, origs: List[OrigProp]): String = {
    s"""trait ${protocolName}App extends ProtocolsApp with XMLBuildersApp {
       |  override def protocols: List[Protocol] = $protocolName :: super.protocols
       |  override def XMLBuilders: List[XMLBuilder[_ <: Product]] = ${origs.map(o ⇒ o.name + "XMLParser").mkString("", " :: ", " :: ")} super.XMLBuilders
       |}
     """.stripMargin
  }

  def makeProtocol(origs: List[OrigProp], schema: String, xsi: String): String = {
    val pckName = "ee.cone.xsd"
    val protocolName = "XSDTestProtocol"
    val lines =
      for {
        orig ← origs
      } yield {
        val fields =
          for {
            field ← orig.fields
          } yield {
            val id = getId(orig.id, field.id)
            getField(id, field.name, field.fType)
          }

        val origId = getId(orig.id, 0)
        getOrig(origId, orig.name, fields)
      }
    getHeader("pckName", protocolName, lines.mkString("\n"), origs.map(_.name)) + "\n\n" + getParsers(schema, xsi, origs) + "\n\n" + getApp(protocolName, origs)
  }
}