package ee.cone.c4actor

@c4component case class DefaultModelRegistryImpl(
  defaultModelFactories: List[DefaultModelFactory[_]]
)(
  val reg: Map[String,DefaultModelFactory[_]] =
    defaultModelFactories.map(f⇒f.valueClass.getName→f).toMap
) extends DefaultModelRegistry {
  def get[P<:Product](className: String): DefaultModelFactory[P] =
    reg(className).asInstanceOf[DefaultModelFactory[P]]
}
