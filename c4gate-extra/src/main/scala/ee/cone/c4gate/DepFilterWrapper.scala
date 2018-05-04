package ee.cone.c4gate

import ee.cone.c4actor.hashsearch.index.StaticHashSearchApi._
import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.{LeafInfoHolder, LeafRegistryApp}
import ee.cone.c4actor.dep.{Dep, InnerDep, RequestDep, SeqParallelDep}
import ee.cone.c4actor.hashsearch.base.{HashSearchDepRequestFactory, HashSearchDepRequestFactoryApp, HashSearchModelsApp}
import ee.cone.c4actor.hashsearch.condition.ConditionCheckWithCl
import ee.cone.c4actor.hashsearch.index.HashSearchStaticLeafFactoryApi
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApi, HashSearchRangerRegistryApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.dep.request.{FLRequestDef, FilterListRequestApp}

trait DepFilterWrapperApp {
  def depFilterWrapper[Model <: Product](modelCl: Class[Model], listName: String): DepFilterWrapperApi[Model]
}

trait DepFilterWrapperMix extends DepFilterWrapperApp with HashSearchStaticLeafFactoryApi with HashSearchRangerRegistryApp {
  def modelConditionFactory: ModelConditionFactory[Unit]

  def defaultModelRegistry: DefaultModelRegistry

  def depFilterWrapper[Model <: Product](modelCl: Class[Model], listName: String): DepFilterWrapperApi[Model] = {
    val modelCondFactoryTyped = modelConditionFactory.ofWithCl(modelCl)
    DepFilterWrapperImpl(Nil, staticLeafFactory.index(modelCl), Seq())({ case Seq() ⇒ modelCondFactoryTyped.any }, listName, modelCl, defaultModelRegistry, modelCondFactoryTyped, hashSearchRangerRegistry)
  }
}

trait DepFilterWrapperCollectorApp {
  def filterWrappers: List[DepFilterWrapperApi[_ <: Product]] = Nil
}

trait DepFilterWrapperCollectorMix
  extends DepFilterWrapperCollectorApp
    with AssemblesApp
    with LeafRegistryApp
    with FilterListRequestApp
    with HashSearchModelsApp
    with HashSearchDepRequestFactoryApp {
  //override def assembles: List[Assemble] = filterWrappers.flatMap(_.getAssembles) ::: super.assembles

  override def leafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]] = filterWrappers.flatMap(_.getLeafs) ::: super.leafs

  override def filterDepList: List[FLRequestDef] = filterWrappers.map(wrapper ⇒ FLRequestDef(wrapper.listName, wrapper.getFilterDep(hashSearchDepRequestFactory))) ::: super.filterDepList

  override def hashSearchModels: List[Class[_ <: Product]] = filterWrappers.map(_.modelCl) ::: super.hashSearchModels
}

case class DepFilterWrapperImpl[Model <: Product, By <: Product, Field](
  leafs: List[LeafInfoHolder[Model, _ <: Product, _]],
  staticIndex: StaticIndexBuilder[Model],
  depAccessSeq: Seq[InnerDep[Option[Access[_ <: Product]]]]
)(
  depToCondFunction: Seq[Option[Access[_ <: Product]]] ⇒ Condition[Model],
  val listName: String,
  val modelCl: Class[Model],
  defaultModelRegistry: DefaultModelRegistry,
  modelConditionFactory: ModelConditionFactory[Model],
  rangerRegistry: HashSearchRangerRegistryApi
) extends DepFilterWrapperApi[Model] {
  def add[SBy <: Product, SField](
    byDep: Dep[Option[Access[SBy]]],
    lens: ProdLens[Model, SField],
    byOptions: List[MetaAttr] = Nil
  )(
    implicit checker: ConditionCheckWithCl[SBy, SField]
  ): DepFilterWrapperApi[Model] = {
    val (sByCl, sFieldCl) = (checker.byCl, checker.fieldCl)
    val rangerOpt: Option[Ranger[SBy, SField]] = rangerRegistry.getByCl(sByCl, sFieldCl)
    val newIndex: StaticIndexBuilder[Model] = rangerOpt.map(staticIndex.add(lens, defaultModelRegistry.get[SBy](sByCl.getName).create(""))(_)).getOrElse(staticIndex)
    val newLeafs = LeafInfoHolder(lens, byOptions, checker, modelCl, sByCl, sFieldCl) :: leafs
    import modelConditionFactory._
    val newFunc: Option[Access[SBy]] ⇒ Condition[Model] = byResolved ⇒ leaf(lens, byResolved.get.initialValue, byOptions)(checker)
    val concatedFunc: Seq[Option[Access[_ <: Product]]] ⇒ Condition[Model] = {
      case Seq(x, rest@_*) ⇒
        val head = newFunc(x.asInstanceOf[Option[Access[SBy]]])
        val tail = depToCondFunction(rest)
        intersect(head, tail)
    }
    DepFilterWrapperImpl(newLeafs, newIndex, byDep.asInstanceOf[InnerDep[Option[Access[_ <: Product]]]] +: depAccessSeq)(concatedFunc, listName, modelCl, defaultModelRegistry, modelConditionFactory, rangerRegistry)
  }

  def getLeafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]] = leafs

  def getAssembles: List[Assemble] = staticIndex.assemble

  def getStaticIndex: StaticIndexBuilder[Model] = staticIndex

  def getFilterDep: HashSearchDepRequestFactory[_] ⇒ Dep[List[Model]] = factory ⇒ {
    val typedFactory = factory.ofWithCl(modelCl)
    for {
      seq ← new SeqParallelDep[Option[Access[_ <: Product]]](depAccessSeq)
      list ← {
        val rq = typedFactory.conditionToHashSearchRequest(depToCondFunction(seq))
        new RequestDep[List[Model]](rq)
      }
    } yield list
  }
}

trait DepFilterWrapperApi[Model <: Product] {
  def add[By <: Product, Field](
    byDep: Dep[Option[Access[By]]],
    lens: ProdLens[Model, Field],
    byOptions: List[MetaAttr] = Nil
  )(
    implicit checker: ConditionCheckWithCl[By, Field]
  ): DepFilterWrapperApi[Model]

  def getLeafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]]

  //def getAssembles: List[Assemble]

  def getStaticIndex: StaticIndexBuilder[Model]

  def getFilterDep: HashSearchDepRequestFactory[_] ⇒ Dep[List[Model]]

  def modelCl: Class[Model]

  def listName: String
}