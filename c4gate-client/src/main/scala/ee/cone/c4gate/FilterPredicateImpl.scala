package ee.cone.c4gate

import ee.cone.c4actor.{Context, ProdLens}



class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory
) extends FilterPredicateBuilder {
  def create[Model](): FilterPredicate[Model] =
    EmptyFilterPredicate[Model]()(sessionAttrAccessFactory)
}

abstract class AbstractFilterPredicate[Model] extends FilterPredicate[Model] {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: FilterPredicateFactory[SBy,SField]
  ): FilterPredicate[Model] =
    FilterPredicateImpl(this,filterKey,lens)(sessionAttrAccessFactory,c)
}

case class EmptyFilterPredicate[Model]()(
  val sessionAttrAccessFactory: SessionAttrAccessFactory
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = Nil
  def of: Context ⇒ Model ⇒ Boolean = local ⇒ model ⇒ true
}

case class FilterPredicateImpl[Model,By<:Product,Field](
  next: FilterPredicate[Model],
  filterKey: SessionAttr[By],
  lens: ProdLens[Model,Field]
)(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  filterPredicateFactory: FilterPredicateFactory[By,Field]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = filterKey :: next.keys
  def of: Context ⇒ Model ⇒ Boolean = local ⇒ {
    val by = sessionAttrAccessFactory.to(filterKey)(local).get.initialValue
    val colPredicate = filterPredicateFactory.create(by)
    val nextPredicate = next.of(local)
    model ⇒ colPredicate(lens.of(model)) && nextPredicate(model)
  }
}
