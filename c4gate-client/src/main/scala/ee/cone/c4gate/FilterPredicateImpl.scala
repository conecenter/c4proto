package ee.cone.c4gate

import ee.cone.c4actor._



class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory,
  modelConditionFactory: ModelConditionFactory[Unit]
) extends FilterPredicateBuilder {
  def create[Model<:Product](): FilterPredicate[Model] =
    EmptyFilterPredicate[Model]()(sessionAttrAccessFactory,modelConditionFactory.of[Model])
}

abstract class AbstractFilterPredicate[Model<:Product] extends FilterPredicate[Model] {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def modelConditionFactory: ModelConditionFactory[Model]
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: ConditionCheck[SBy,SField]
  ): FilterPredicate[Model] =
    FilterPredicateImpl(this,filterKey,lens)(sessionAttrAccessFactory,modelConditionFactory,c)
}

case class EmptyFilterPredicate[Model<:Product]()(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  val modelConditionFactory: ModelConditionFactory[Model]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = Nil
  def of: Context ⇒ Condition[Model] = local ⇒ modelConditionFactory.any
}

case class FilterPredicateImpl[Model<:Product,By<:Product,Field](
  next: FilterPredicate[Model],
  filterKey: SessionAttr[By],
  lens: ProdLens[Model,Field]
)(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  val modelConditionFactory: ModelConditionFactory[Model],
  filterPredicateFactory: ConditionCheck[By,Field]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = filterKey :: next.keys
  def of: Context ⇒ Condition[Model] = local ⇒ {
    val by = sessionAttrAccessFactory.to(filterKey)(local).get.initialValue
    val colPredicate = modelConditionFactory.leaf(lens,by)(filterPredicateFactory)
    val nextPredicate = next.of(local)
    modelConditionFactory.intersect(colPredicate,nextPredicate)
  }
}
