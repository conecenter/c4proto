package ee.cone.c4actor.hashsearch

import ee.cone.c4actor._

case class FilterPredicateDepImpl[Model <: Product, By <: Product, Field](
  accesses: List[Access[_]], condition: Condition[Model]
)(
  modelConditionFactory: ModelConditionFactory[Model]
) extends DepFilterPredicate[Model] {

  import modelConditionFactory._

  def add[SBy <: Product, SField](by: Option[Access[SBy]], lens: ProdLens[Model, SField])(
    implicit c: ConditionCheck[SBy, SField]
  ): DepFilterPredicate[Model] = {
    val nCond = intersect(leaf(lens, by.get.initialValue, by.get.metaList)(c), condition)
    FilterPredicateDepImpl(by.toList ::: accesses, nCond)(modelConditionFactory)
  }
}

trait DepFilterPredicate[Model <: Product] extends FilterPredicateApi[Model] {
  def add[By <: Product, Field](by: Option[Access[By]], lens: ProdLens[Model, Field])(implicit c: ConditionCheck[By, Field]): DepFilterPredicate[Model]
}
