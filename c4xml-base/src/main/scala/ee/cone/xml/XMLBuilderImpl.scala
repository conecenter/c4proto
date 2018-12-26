package ee.cone.xml

case class XMLBuilderRegistryImpl(builders: List[XMLBuilder[_ <: Product]]) extends XMLBuilderRegistry {
  val byName: Map[String, XMLBuilder[_ <: Product]] = builders.map(b ⇒ b.name → b).toMap
}

trait XMLBuilderRegistryMix extends XMLBuildersApp {
  def XMLBuilderRegistry: XMLBuilderRegistry = XMLBuilderRegistryImpl(XMLBuilders)
}
