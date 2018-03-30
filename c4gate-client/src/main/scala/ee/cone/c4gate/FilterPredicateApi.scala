package ee.cone.c4gate

import ee.cone.c4actor._

trait FilterPredicateBuilder {
  def create[Model<:Product]: Context ⇒ FilterPredicate[Model]
}

trait FilterPredicate[Model<:Product] extends FilterPredicateApi[Model] {
  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: ConditionCheck[By,Field]): FilterPredicate[Model]
}
