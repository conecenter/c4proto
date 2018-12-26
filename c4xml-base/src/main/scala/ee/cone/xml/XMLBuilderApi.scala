package ee.cone.xml

trait XMLBuilder[A <: Product] {
  def name: String
  def fromXML(in: xml.Node): A
  def productToXML(a: Product): String =
    toXML(a.asInstanceOf[A]).toString()
  def toXML(a: A): xml.Elem
}

trait XMLBuildersApp {
  def XMLBuilders: List[XMLBuilder[_ <: Product]] = Nil
}

trait XMLBuilderRegistry {
  def byName: Map[String, XMLBuilder[_ <: Product]]
}