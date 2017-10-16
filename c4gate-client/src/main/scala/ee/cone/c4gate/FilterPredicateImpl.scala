package ee.cone.c4gate

import ee.cone.c4actor._



class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory
) extends FilterPredicateBuilder {
  def create[Model](): FilterPredicate[Model] =
    EmptyFilterPredicate[Model]()(sessionAttrAccessFactory)
}

abstract class AbstractFilterPredicate[Model] extends FilterPredicate[Model] {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: LeafConditionFactory[SBy,SField]
  ): FilterPredicate[Model] =
    FilterPredicateImpl(this,filterKey,lens)(sessionAttrAccessFactory,c)
}

case class EmptyFilterPredicate[Model]()(
  val sessionAttrAccessFactory: SessionAttrAccessFactory
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = Nil
  def of: Context ⇒ Condition[Model] = local ⇒ AnyCondition()
}

case class FilterPredicateImpl[Model,By<:Product,Field](
  next: FilterPredicate[Model],
  filterKey: SessionAttr[By],
  lens: ProdLens[Model,Field]
)(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  filterPredicateFactory: LeafConditionFactory[By,Field]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = filterKey :: next.keys
  def of: Context ⇒ Condition[Model] = local ⇒ {
    val by = sessionAttrAccessFactory.to(filterKey)(local).get.initialValue
    val colPredicate = filterPredicateFactory.create(lens,by)
    val nextPredicate = next.of(local)
    IntersectCondition(colPredicate,nextPredicate)
  }
}
