
package ee.cone.xml

import javax.xml.validation.Schema

import scala.util.Try
import scala.xml.Elem
import scala.xml.factory.XMLLoader

class XMLParserImpl(
  schemaResource: String
) extends XMLParser {
  val reader: XMLLoader[Elem] = {
    val schemaLang = javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
    val xsdFile = java.nio.file.Paths.get(schemaResource)
    val readOnly = java.nio.file.StandardOpenOption.READ
    val inputStream = java.nio.file.Files.newInputStream(xsdFile, readOnly)
    val xsdStream = new javax.xml.transform.stream.StreamSource(inputStream)
    val schema: Schema = javax.xml.validation.SchemaFactory.newInstance(schemaLang).newSchema(xsdStream)

    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.setSchema(schema)
    val validatingParser = factory.newSAXParser()
    val sitemap: XMLLoader[Elem] = new scala.xml.factory.XMLLoader[scala.xml.Elem] {
      override def parser = validatingParser
      override def adapter =
        new scala.xml.parsing.NoBindingFactoryAdapter
          with scala.xml.parsing.ConsoleErrorHandler

    }
    inputStream.close()
    sitemap
  }

  def fromXML(xmlStr: String): Option[Messagename] = {
    for {
      xml ← Try(reader.loadString(xmlStr)).toOption
    } yield {
      scalaxb.fromXML[Messagename](xml)
    }
  }

  def toXML(product: Messagename): Option[String] = {
    Try(scalaxb.toXML[Messagename](product, "Messagename", defaultScope).toString()).toOption
  }
}

