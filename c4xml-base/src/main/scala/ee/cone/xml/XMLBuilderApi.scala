package ee.cone.xml

trait XMLBuilder[A <: Product] {
  def name: String
  def from(fields: List[String]): A
  def to(a: A): xml.NodeBuffer
}

trait XMLBuildersApp {
  def XMLBuilders: List[XMLBuilder[_ <: Product]] = Nil
}
