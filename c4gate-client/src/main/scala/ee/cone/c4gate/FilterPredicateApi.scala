package ee.cone.c4gate

import ee.cone.c4actor.{Context, ProdLens}

trait FilterPredicateBuilder {
  def create[Model](): FilterPredicate[Model]
}

trait FilterPredicateFactory[By,Field] {
  def create(by: By): Field⇒Boolean
}

trait FilterPredicate[Model] {
  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: FilterPredicateFactory[By,Field]): FilterPredicate[Model]
  def keys: List[SessionAttr[Product]]
  def of: Context ⇒ Model ⇒ Boolean
}
