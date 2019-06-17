package ee.cone.c4gate.deep_session

import ee.cone.c4actor.ModelConditionFactory
import ee.cone.c4gate.{FilterPredicateBuilder, FilterPredicateBuilderImpl}

trait DeepFilterPredicateBuilderApp {
  def deepSessionAttrAccessFactory: DeepSessionAttrAccessFactory
  def modelConditionFactory: ModelConditionFactory[Unit]
  lazy val filterPredicateBuilder: FilterPredicateBuilder =
    new FilterPredicateBuilderImpl(deepSessionAttrAccessFactory,modelConditionFactory)
}
