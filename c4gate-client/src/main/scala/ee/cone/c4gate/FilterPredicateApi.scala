package ee.cone.c4gate

import ee.cone.c4actor.{Condition, Context, LeafConditionFactory, ProdLens}

trait FilterPredicateBuilder {
  def create[Model](): FilterPredicate[Model]
}

trait FilterPredicate[Model] {
  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: LeafConditionFactory[By,Field]): FilterPredicate[Model]
  def keys: List[SessionAttr[Product]]
  def of: Context ⇒ Condition[Model]
}
