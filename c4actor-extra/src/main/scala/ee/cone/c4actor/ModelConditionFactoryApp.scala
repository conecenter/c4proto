package ee.cone.c4actor

trait ModelConditionFactoryApp {
  def modelConditionFactory: ModelConditionFactory[_]
}

trait ModelConditionFactoryMix extends ModelConditionFactoryApp {
  def modelConditionFactory: ModelConditionFactory[_]
}
