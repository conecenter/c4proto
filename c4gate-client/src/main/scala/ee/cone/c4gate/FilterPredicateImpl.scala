package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Single

@c4component case class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory,
  modelConditionFactory: ModelConditionFactory
) extends FilterPredicateBuilder {
  def create[Model<:Product]: Context ⇒ FilterPredicate[Model] = local ⇒ {
    val condFactory = modelConditionFactory.of[Model]
    FilterPredicateImpl(Nil,condFactory.any)(sessionAttrAccessFactory,condFactory,local)
  }
}

case class FilterPredicateImpl[Model<:Product,By<:Product,Field](
  accesses: List[Access[_]], condition: Condition[Model]
)(
  sessionAttrAccessFactory: SessionAttrAccessFactory,
  modelConditionBuilder: ModelConditionBuilder[Model],
  local: Context
) extends FilterPredicate[Model] {
  import modelConditionBuilder._
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: ConditionCheck[SBy,SField]
  ): FilterPredicate[Model] = {
    val by = sessionAttrAccessFactory.to(filterKey)(local)
    val nCond = intersect(leaf(lens,by.get.initialValue,by.get.metaList)(c),condition)
    FilterPredicateImpl(by.toList ::: accesses, nCond)(sessionAttrAccessFactory,modelConditionBuilder,local)
  }
}
