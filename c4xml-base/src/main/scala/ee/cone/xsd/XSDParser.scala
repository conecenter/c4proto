package ee.cone.xsd

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.xml.{Elem, XML}

case class OrigProp(name: String, fields: List[FieldProp], id: Int)
case class FieldProp(name: String, fType: String, id: Int)

object XSDParser {
  def getType(str: String): String = str match {
    case "xs:string" ⇒ "String"
    case "xs:long" ⇒ "Long"
    case "xs:int" ⇒ "Int"
    case _ ⇒ throw new Exception("Can't find given type:" + str)
  }

  def parse(elem: xml.Node): List[OrigProp] =
    for {
      (orig, id) ← (elem \ "element").toList.zipWithIndex
      fieldSeq ← orig \\ "sequence"
    } yield {
      val origName = orig \@ "name"
      val fields =
        for {
          (elem, fId) ← (fieldSeq \ "element").zipWithIndex.toList
        } yield {
          FieldProp(elem \@ "name", getType(elem \@ "type"), fId + 1)
        }
      OrigProp(origName, fields, id)
    }
}

object XSDParserTest{
  def main(args: Array[String]): Unit = {
    val xml: Elem = XML.loadFile("./schema.xsd")
    val data = XSDParser.parse(xml)
    val protocol = XMLProtocolGeneration.makeProtocol(data, "file:///C:/Users/User/Desktop/Cone/C4PROTO/c4xml-base/schema.xsd","http://www.w3.org/2001/XMLSchema-instance")
    Files.write(Paths.get("./src/main/scala/ee/cone/xsd/XSDTestProtocol.scala"), protocol.getBytes(StandardCharsets.UTF_8))
  }
}
