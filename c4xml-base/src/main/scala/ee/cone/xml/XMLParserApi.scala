package ee.cone.xml

trait XMLParser {
  def fromXML(xml: String): Option[Messagename]
  def toXML(product: Messagename): Option[String]
}
