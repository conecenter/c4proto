package ee.cone.c4gate

import ee.cone.c4actor.{Condition, Context, ConditionCheck, ProdLens}

trait FilterPredicateBuilder {
  def create[Model<:Product](): FilterPredicate[Model]
}

trait FilterPredicate[Model<:Product] {
  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: ConditionCheck[By,Field]): FilterPredicate[Model]
  def keys: List[SessionAttr[Product]]
  def of: Context ⇒ Condition[Model]
}
