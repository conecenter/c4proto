package ee.cone.c4actor.utils

import ee.cone.c4actor.CheckedMap

class GeneralizedOrigRegistryImpl(
  defaultModelFactories: List[GeneralizedOrigFactory[_]]
)(
  val reg: Map[String, GeneralizedOrigFactory[_]] =
  CheckedMap(defaultModelFactories.map(f ⇒ f.valueClass.getName → f))
) extends GeneralizedOrigRegistry {
  def get[P <: Product](className: String): GeneralizedOrigFactory[P] =
    reg(className).asInstanceOf[GeneralizedOrigFactory[P]]
}
