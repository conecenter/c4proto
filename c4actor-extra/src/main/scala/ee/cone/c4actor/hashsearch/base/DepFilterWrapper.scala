package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor.HashSearch.StaticIndexBuilder
import ee.cone.c4actor._
import ee.cone.c4actor.dep.{Dep, RequestDep}
import ee.cone.c4actor.dep.request.LeafInfoHolder
import ee.cone.c4assemble.Assemble

trait DepFilterWrapperApp {
  def depFilterWrapper[Model <: Product]: DepFilterWrapperApi[Model]
}

trait DepFilterWrapperCollectorApp {
  def filterWrappers: List[DepFilterWrapperApi[_ <: Product]] = Nil
}

trait DepFilterWrapperCollectorMix {
  //Finish here
}

case class DepFilterWrapperImpl[Model <: Product, By <: Product, Field](
  depCondition: Dep[Condition[Model]],
  leafs: List[LeafInfoHolder[Model, _ <: Product, _]],
  staticIndex: StaticIndexBuilder[Model]
)(
  modelCl: Class[Model],
  defaultModelRegistry: DefaultModelRegistry,
  modelConditionFactory: ModelConditionFactory[Model]
) extends DepFilterWrapperApi[Model] {
  def add[SBy <: Product, SField](
    byDep: Dep[Option[Access[SBy]]],
    lens: ProdLens[Model, SField],
    byOptions: List[MetaAttr] = Nil, // take from access
    byCl: Class[SBy], // move to ranger
    fieldCl: Class[SField] // move to ranger
  )(
    implicit checker: ConditionCheck[SBy, SField], ranger: Ranger[SBy, SField]
  ): DepFilterWrapperApi[Model] = {
    val newIndex: StaticIndexBuilder[Model] = staticIndex.add(lens, defaultModelRegistry.get[SBy](byCl.getName).create(""))(ranger)
    val newLeafs = LeafInfoHolder(lens, byOptions, checker, modelCl, byCl, fieldCl) :: leafs
    import modelConditionFactory._
    val newDep = for {
      prev ← depCondition
      by ← byDep
    } yield {
      intersect(leaf(lens, by.get.initialValue, by.get.metaList)(checker), prev)
    }
    DepFilterWrapperImpl(newDep, newLeafs, newIndex)(modelCl, defaultModelRegistry, modelConditionFactory)
  }

  def getLeafs: List[LeafInfoHolder[Model, _ <: Product, _]] = leafs

  def getAssembles: List[Assemble] = staticIndex.assemble

  def getFilterDep: HashSearchDepRequestFactory[Model] ⇒ Dep[List[Model]] = factory ⇒ {
    for {
      cond ← depCondition
      list ← {
        val rq = factory.conditionToHashSearchRequest(cond)
        new RequestDep[List[Model]](rq)
      }
    } yield list
  }
}

trait DepFilterWrapperApi[Model <: Product] {
  def add[By <: Product, Field](
    byDep: Dep[Option[Access[By]]],
    lens: ProdLens[Model, Field],
    byOptions: List[MetaAttr],
    byCl: Class[By],
    fieldCl: Class[Field]
  )(
    implicit checker: ConditionCheck[By, Field], ranger: Ranger[By, Field]
  ): DepFilterWrapperApi[Model]

  def getLeafs: List[LeafInfoHolder[Model, _ <: Product, _]]

  def getAssembles: List[Assemble]

  def getFilterDep: HashSearchDepRequestFactory[Model] ⇒ Dep[List[Model]]
}