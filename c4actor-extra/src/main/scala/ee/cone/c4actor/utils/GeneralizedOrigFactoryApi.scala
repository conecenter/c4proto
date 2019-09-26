package ee.cone.c4actor.utils

import ee.cone.c4actor.Types.SrcId

trait GeneralizedOrigRegistry {
  def get[P <: Product](className: String): GeneralizedOrigFactory[P]
}

abstract class GeneralizedOrigFactory[P](val valueClass: Class[P], val create: SrcId => P => P)

trait GeneralizedOrigFactoriesApp {
  def generalizedOrigFactories: List[GeneralizedOrigFactory[_]] = Nil
}

trait GeneralizedOrigRegistryApi {
  def generalizedOrigRegistry: GeneralizedOrigRegistry
}