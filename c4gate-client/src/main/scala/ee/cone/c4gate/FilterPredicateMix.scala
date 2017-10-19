package ee.cone.c4gate

import ee.cone.c4actor.ModelConditionFactory

trait FilterPredicateBuilderApp {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def modelConditionFactory: ModelConditionFactory[Unit]
  lazy val filterPredicateBuilder: FilterPredicateBuilder =
    new FilterPredicateBuilderImpl(sessionAttrAccessFactory,modelConditionFactory)
}
