package ee.cone.xml

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.xml.{Elem, XML}

case class OrigProp(name: String, fields: List[FieldProp], id: Int)
case class FieldProp(name: String, fType: String, id: Int)

object XSDParser {
  def getType(str: String): String = str match {
    case "xs:string" ⇒ "String"
    case "xs:long" ⇒ "Long"
    case _ ⇒ throw new Exception("Can't find given type:"+str)
  }

  def main(args: Array[String]): Unit = {
    val xml: Elem = XML.loadFile("./schema.xsd")
    val data = for {
      (orig, id) ← (xml \ "element").toList.zipWithIndex
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
    val protocol = XMLProtocolGeneration.makeProtocol(data)
    Files.write(Paths.get("./src/main/scala/ee/cone/xml/XMLTestProtocol.scala"), protocol.getBytes(StandardCharsets.UTF_8))
  }
}
