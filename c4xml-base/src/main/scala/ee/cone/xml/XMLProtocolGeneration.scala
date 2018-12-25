package ee.cone.xml

object Hex2 {
  def apply(i: Long): String = "0x%04x".format(i)
}

object XMLProtocolGeneration {
  val offset = 40960

  def getId(outer: Int, inner: Int): String = {
    Hex2(inner + outer * 24 + offset)
  }

  def getHeader(pckName: String,name: String, body: String): String = {
    s"""package $pckName
       |
       |import ee.cone.c4proto._
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

  def makeProtocol(origs: List[OrigProp]): String = {
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
    getHeader("ee.cone.xml", "XMLTestProtocol", lines.mkString("\n"))
  }
}


object Test {
  def main(args: Array[String]): Unit = {
    println(XMLProtocolGeneration.getId(100, 22))
  }
}