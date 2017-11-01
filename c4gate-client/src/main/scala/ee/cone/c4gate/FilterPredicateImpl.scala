package ee.cone.c4gate

import ee.cone.c4actor._



@c4component case class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory,
  modelConditionFactory: ModelConditionFactory
) extends FilterPredicateBuilder {
  def create[Model<:Product](): FilterPredicate[Model] =
    EmptyFilterPredicate[Model]()(sessionAttrAccessFactory,modelConditionFactory.of[Model])
}

abstract class AbstractFilterPredicate[Model<:Product] extends FilterPredicate[Model] {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def modelConditionBuilder: ModelConditionBuilder[Model]
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: ConditionCheck[SBy,SField]
  ): FilterPredicate[Model] =
    FilterPredicateImpl(this,filterKey,lens)(sessionAttrAccessFactory,modelConditionBuilder,c)
}

case class EmptyFilterPredicate[Model<:Product]()(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  val modelConditionBuilder: ModelConditionBuilder[Model]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = Nil
  def of: Context ⇒ Condition[Model] = local ⇒ modelConditionBuilder.any
}

case class FilterPredicateImpl[Model<:Product,By<:Product,Field](
  next: FilterPredicate[Model],
  filterKey: SessionAttr[By],
  lens: ProdLens[Model,Field]
)(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  val modelConditionBuilder: ModelConditionBuilder[Model],
  filterPredicateFactory: ConditionCheck[By,Field]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = filterKey :: next.keys
  def of: Context ⇒ Condition[Model] = local ⇒ {
    val by = sessionAttrAccessFactory.to(filterKey)(local).get.initialValue
    val colPredicate = modelConditionBuilder.leaf(lens,by)(filterPredicateFactory)
    val nextPredicate = next.of(local)
    modelConditionBuilder.intersect(colPredicate,nextPredicate)
  }
}
