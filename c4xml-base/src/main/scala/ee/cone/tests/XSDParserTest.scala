package ee.cone.tests

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import ee.cone.xsd.{XMLCaseClassesGeneration, XSDParser}

import scala.xml.{Elem, XML}

object XSDParserTest {
  def main(args: Array[String]): Unit = {
    val xml: Elem = XML.loadFile("./schema.xsd")
    val data = XSDParser.parse(xml)
    val protocol = XMLCaseClassesGeneration.makeCaseClasses("ee.cone.tests", data, "file:///C:/Users/User/Desktop/Cone/C4PROTO/c4xml-base/schema.xsd", "http://www.w3.org/2001/XMLSchema-instance")
    Files.write(Paths.get("./src/main/scala/ee/cone/tests/XSDTestResult.scala"), protocol.getBytes(StandardCharsets.UTF_8))
  }
}
