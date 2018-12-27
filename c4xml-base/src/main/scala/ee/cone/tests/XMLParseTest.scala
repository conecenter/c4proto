package ee.cone.tests

import ee.cone.xml.{XMLBuilder, XMLBuilderRegistryImpl, XMLParserImpl}

import scala.xml.NodeBuffer

object XMLParseTest {
  val testXML: String = {
    """<TestBoo xsi:noNamespaceSchemaLocation="file:///C:/Users/User/Desktop/Cone/C4PROTO/c4xml-base/schema.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      |        <eventId>5757f9c5-c837-4fcf-80df-80d412e96736</eventId>
      |        <cargoName>AD7114</cargoName>
      |        <shipTo>123</shipTo>
      |        <shipDate>1545730939116</shipDate>
      |      </TestBoo>""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    val parser = new XMLParserImpl(XMLBuilderRegistryImpl(TestBooXMLParser :: Nil),"./schema.xsd")
    val testOrig = parser.fromXML(testXML)
    println(testOrig.get)
    val testXml = parser.toXML(testOrig.get)
    println(testXml.get)
  }
}
