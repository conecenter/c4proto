package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.{LeafInfoHolder, LeafRegistryApp}
import ee.cone.c4actor.dep.{Dep, InnerDep, RequestDep, SeqParallelDep}
import ee.cone.c4actor.hashsearch.base.{HashSearchDepRequestFactory, HashSearchDepRequestFactoryApp}
import ee.cone.c4actor.hashsearch.condition.ConditionCheckWithCl
import ee.cone.c4actor.hashsearch.rangers.HashSearchRangerRegistryApp
import ee.cone.c4gate.dep.request.{FLRequestDef, FilterListRequestApp}

trait DepFilterWrapperApp {
  def depFilterWrapper[Model <: Product](modelCl: Class[Model], listName: String, matches: List[String] = ".*" :: Nil): DepFilterWrapperApi[Model]
}

trait DepFilterWrapperMix extends DepFilterWrapperApp with HashSearchRangerRegistryApp {
  def modelConditionFactory: ModelConditionFactory[Unit]

  def defaultModelRegistry: DefaultModelRegistry

  def depFilterWrapper[Model <: Product](modelCl: Class[Model], listName: String, matches: List[String] = ".*" :: Nil): DepFilterWrapperApi[Model] = {
    val modelCondFactoryTyped = modelConditionFactory.ofWithCl(modelCl)
    DepFilterWrapperImpl(Nil, Seq())({ case Seq() ⇒ modelCondFactoryTyped.any }, listName, modelCl, modelCondFactoryTyped, matches)
  }
}

trait DepFilterWrapperCollectorApp {
  def filterWrappers: List[DepFilterWrapperApi[_ <: Product]] = Nil
}

trait DepFilterWrapperCollectorMix
  extends DepFilterWrapperCollectorApp
    with LeafRegistryApp
    with FilterListRequestApp
    with HashSearchDepRequestFactoryApp {

  override def leafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]] = filterWrappers.flatMap(_.getLeafs) ::: super.leafs

  override def filterDepList: List[FLRequestDef] = filterWrappers.map(wrapper ⇒ FLRequestDef(wrapper.listName, wrapper.getFilterDep(hashSearchDepRequestFactory), wrapper.matches)) ::: super.filterDepList
}

case class DepFilterWrapperImpl[Model <: Product, By <: Product, Field](
  leafs: List[LeafInfoHolder[Model, _ <: Product, _]],
  depAccessSeq: Seq[InnerDep[Option[Access[_ <: Product]]]]
)(
  depToCondFunction: Seq[Option[Access[_ <: Product]]] ⇒ Condition[Model],
  val listName: String,
  val modelCl: Class[Model],
  modelConditionFactory: ModelConditionFactory[Model],
  val matches: List[String]
) extends DepFilterWrapperApi[Model] {
  def add[SBy <: Product, SField](
    byDep: Dep[Option[Access[SBy]]],
    lens: ProdLens[Model, SField],
    byOptions: List[MetaAttr] = Nil
  )(
    implicit checker: ConditionCheckWithCl[SBy, SField]
  ): DepFilterWrapperApi[Model] = {
    val (sByCl, sFieldCl) = (checker.byCl, checker.fieldCl)
    val newLeafs = LeafInfoHolder(lens, byOptions, checker, modelCl, sByCl, sFieldCl) :: leafs
    import modelConditionFactory._
    val newFunc: Option[Access[SBy]] ⇒ Condition[Model] = byResolved ⇒ leaf[SBy, SField](lens, byResolved.get.initialValue, byOptions)(checker)
    val concatFunc: Seq[Option[Access[_ <: Product]]] ⇒ Condition[Model] = {
      case Seq(x, rest@_*) ⇒
        val access: Option[Access[SBy]] = x.asInstanceOf[Option[Access[SBy]]]
        val head: Condition[Model] = newFunc(access)
        val tail: Condition[Model] = depToCondFunction(rest)
        intersect(head, tail)
    }
    DepFilterWrapperImpl(newLeafs, byDep.asInstanceOf[InnerDep[Option[Access[_ <: Product]]]] +: depAccessSeq)(concatFunc, listName, modelCl, modelConditionFactory, matches)
  }

  def getLeafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]] = leafs

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

  def getFilterDep: HashSearchDepRequestFactory[_] ⇒ Dep[List[Model]]

  def modelCl: Class[Model]

  def listName: String

  def matches: List[String]
}