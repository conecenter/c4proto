package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{FilterPredicateApi, _}

trait FilterPredicateBuilder {
  def create[Model<:Product]: Context => FilterPredicate[Model]

  def createWithPK[Model <: Product](filterPK: SrcId): Context => FilterPredicate[Model]
}

trait FilterPredicate[Model<:Product] extends FilterPredicateApi[Model] {

  def addAccess[By<:Product,Field](filterAccess: Access[By], lens: ProdLens[Model,Field])(implicit c: ConditionCheck[By,Field]): FilterPredicate[Model]

  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: ConditionCheck[By,Field]): FilterPredicate[Model]
}
