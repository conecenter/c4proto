package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Single

class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory,
  modelConditionFactory: ModelConditionFactory[Unit]
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
  modelConditionFactory: ModelConditionFactory[Model],
  local: Context
) extends FilterPredicate[Model] {
  import modelConditionFactory._
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: ConditionCheck[SBy,SField]
  ): FilterPredicate[Model] = {
    val by = sessionAttrAccessFactory.to(filterKey)(local)
    val nCond = intersect(leaf(lens,by.get.initialValue,by.get.metaList)(c),condition)
    FilterPredicateImpl(by.toList ::: accesses, nCond)(sessionAttrAccessFactory,modelConditionFactory,local)
  }
}

/*
case class AccessSplitter[P,I](lens: ProdLens[P,I])(val valueClass: Class[P])
trait AccessSplitterRegistry {
  def split: List[Access[_]] ⇒ List[Access[_]]
}

class AccessSplitterRegistryImpl(
  list: List[AccessSplitter[_,_]]
)(
  map: Map[String,List[AccessSplitter[_,_]]] = list.groupBy(_.valueClass.getName)
) extends AccessSplitterRegistry {
  def split: List[Access[_]] ⇒ List[Access[_]] = accesses ⇒ for {
    access ← accesses
    sAccess ← map.get(access.initialValue.getClass.getName)
      .fold(List(access))(splitters⇒split(splitters.map(access to _.lens)))
  } yield sAccess
}
*/