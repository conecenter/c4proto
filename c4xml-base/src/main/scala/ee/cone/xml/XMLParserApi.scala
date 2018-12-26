package ee.cone.xml

trait XMLParser {
  def fromXML(xml: String): Option[Product]
  def toXML(product: Product): Option[String]
}
