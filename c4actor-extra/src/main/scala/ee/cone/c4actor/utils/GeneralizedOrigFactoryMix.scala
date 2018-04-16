package ee.cone.c4actor.utils

trait GeneralizedOrigFactoryMix
  extends GeneralizedOrigFactoriesApp
    with GeneralizedOrigRegistryApi {
  def generalizedOrigRegistry: GeneralizedOrigRegistry = new GeneralizedOrigRegistryImpl(generalizedOrigFactories)()
}
