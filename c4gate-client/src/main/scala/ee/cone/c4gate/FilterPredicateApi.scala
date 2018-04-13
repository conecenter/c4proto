package ee.cone.c4gate

import ee.cone.c4actor._

trait FilterPredicateBuilder {
  def create[Model<:Product]: Context ⇒ FilterPredicate[Model]
}

trait FilterPredicate[Model<:Product] {
  def add[By<:Product,Field](filterAccess: Access[By], lens: ProdLens[Model,Field])(implicit c: ConditionCheck[By,Field]): FilterPredicate[Model]
  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: ConditionCheck[By,Field]): FilterPredicate[Model]
  def accesses: List[Access[_]]
  def condition: Condition[Model]
}
