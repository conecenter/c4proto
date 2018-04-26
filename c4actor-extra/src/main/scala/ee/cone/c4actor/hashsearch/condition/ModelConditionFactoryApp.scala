package ee.cone.c4actor.hashsearch.condition

import ee.cone.c4actor.ModelConditionFactory

trait ModelConditionFactoryApp {
  def modelConditionFactory: ModelConditionFactory[_]
}

trait ModelConditionFactoryMix extends ModelConditionFactoryApp {
  def modelConditionFactory: ModelConditionFactory[_]
}
