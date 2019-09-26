package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

trait DefaultModelRegistry {
  def get[P<:Product](className: String): DefaultModelFactory[P]
}

abstract class DefaultModelFactory[P](val valueClass: Class[P], val create: SrcId=>P)

trait DefaultModelFactoriesApp {
  def defaultModelFactories: List[DefaultModelFactory[_]] = Nil
}