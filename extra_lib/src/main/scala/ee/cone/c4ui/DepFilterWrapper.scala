package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.LeafInfoHolderTypes.ProductLeafInfoHolder
import ee.cone.c4actor.dep.request._
import ee.cone.c4actor.dep.{Dep, DepFactory, DepFactoryApp}
import ee.cone.c4actor.dep_impl.RequestDep
import ee.cone.c4actor.hashsearch.base.{HashSearchDepRequestFactory, HashSearchDepRequestFactoryApp, HashSearchDepRequestFactoryCreator}
import ee.cone.c4actor.hashsearch.condition.ConditionCheckWithCl
import ee.cone.c4actor.hashsearch.rangers.HashSearchRangerRegistryApp
import ee.cone.c4di.{Component, ComponentsApp, c4, provide}
import ee.cone.c4ui.dep.request.{FLRequestDef, FilterListRequestApp}

import scala.collection.immutable.Seq

case class DepFilterPK(filtersPK: String, matches: List[String])

trait DepFilterWrapperApp {
  def depFilterWrapper[Model <: Product](modelCl: Class[Model], listName: String, matches: List[DepFilterPK] = DepFilterPK("", ".*" :: Nil) :: Nil): DepFilterWrapper[Model]
}

trait DepFilterWrapperMix extends DepFilterWrapperApp with HashSearchRangerRegistryApp with DepFactoryApp {
  def modelConditionFactory: ModelConditionFactory[Unit]

  def modelFactory: ModelFactory

  def depFilterWrapper[Model <: Product](modelCl: Class[Model], listName: String, matches: List[DepFilterPK] = DepFilterPK("", ".*" :: Nil) :: Nil): DepFilterWrapper[Model] = {
    val modelCondFactoryTyped = modelConditionFactory.ofWithCl(modelCl)
    DepFilterWrapperImpl(Nil, listName, matches)(Seq({ _ => depFactory.resolvedRequestDep(modelCondFactoryTyped.any) }), modelCl, modelCondFactoryTyped, depFactory)
  }
}

trait DepFilterWrapperProvider {
  def filterWrappers: List[DepFilterWrapper[_ <: Product]]
}

trait DepFilterWrapperCollectorMixBase
  extends HashSearchDepRequestFactoryApp

@c4("DepFilterWrapperCollectorMix") final class DepFilterWrapperCollectorLeafs(
  providers: List[DepFilterWrapperProvider],
  creator: HashSearchDepRequestFactoryCreator
) {
  @provide def leafs: Seq[ProductLeafInfoHolder] = for {
    provider <- providers
    filterWrapper <- provider.filterWrappers
    leaf <- filterWrapper.getLeafs
  } yield leaf

  @provide def filterDepList: Seq[FLRequestDef] =
    providers.flatMap(_.filterWrappers).flatMap(_.filterRequests(creator))
}


case class DepFilterWrapperImpl[Model <: Product](
  leafs: List[LeafInfoHolder[Model, _ <: Product, _]],
  listName: String,
  matches: List[DepFilterPK]
)(
  depConditionSeq: Seq[String => Dep[Condition[Model]]],
  val modelCl: Class[Model],
  modelConditionFactory: ModelConditionFactory[Model],
  depFactory: DepFactory
) extends DepFilterWrapper[Model] {
  def add[By <: Product, Field](
    byDep: String => Dep[Access[By]],
    lens: ProdGetter[Model, Field],
    byOptions: List[AbstractMetaAttr] = Nil
  )(
    implicit checker: ConditionCheckWithCl[By, Field]
  ): DepFilterWrapper[Model] = {
    val (sByCl, sFieldCl) = (checker.byCl, checker.fieldCl)
    val newLeafs = LeafInfoHolder(lens, byOptions, checker, modelCl, sByCl, sFieldCl) :: leafs
    import modelConditionFactory._
    val newDep: String => Dep[Condition[Model]] = pk =>
      for {
        byResolved <- byDep(pk)
      } yield {
        leaf[By, Field](lens, byResolved.initialValue, byOptions)(checker)
      }
    DepFilterWrapperImpl(
      newLeafs,
      listName,
      matches
    )(newDep +: depConditionSeq,
      modelCl,
      modelConditionFactory,
      depFactory
    )
  }

  def getLeafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]] = leafs

  def getFilterDep: HashSearchDepRequestFactoryCreator => String => Dep[List[Model]] = factory => filterPK => {
    val typedFactory = factory.ofWithCl(modelCl)
    for {
      conditions <- depFactory.parallelSeq[Condition[Model]](depConditionSeq.map(_.apply(filterPK)))
      list <- {
        val rq = typedFactory.conditionToHashSearchRequest(conditions.reduce(modelConditionFactory.intersect))
        new RequestDep[List[Model]](rq)
      }
    } yield list
  }

  def filterRequests: HashSearchDepRequestFactoryCreator => List[FLRequestDef] = factory =>
    for {
      depFilterPK <- matches
      DepFilterPK(filtersPK, matches) = depFilterPK
      preAppliedLambda = getFilterDep(factory)
    } yield {
      FLRequestDef(listName, filtersPK, matches)(preAppliedLambda(filtersPK).asInstanceOf[Dep[List[_]]])
    }

  def addSwitch(
    isLeftDep: String => Dep[Boolean],
    partLeft: DepFilterWrapperPartApi[Model],
    partRight: DepFilterWrapperPartApi[Model]
  ): DepFilterWrapper[Model] = {
    val newDep: String => Dep[Condition[Model]] = pk => for {
      isLeft <- isLeftDep(pk)
      depCondition = if (isLeft) partLeft.depCondition else partRight.depCondition
      condition <- depCondition(pk)
    } yield {
      condition
    }

    DepFilterWrapperImpl[Model](
      partLeft.leafs ::: partRight.leafs ::: leafs,
      listName,
      matches
    )(
      newDep +: depConditionSeq,
      modelCl,
      modelConditionFactory,
      depFactory
    )
  }

  def getPart: DepFilterWrapperPrePartApi[Model] =
    DepFilterWrapperPrePartImpl((leafs.size, listName, matches.size))(modelCl, modelConditionFactory, depFactory)
}

case class DepFilterWrapperPrePartImpl[Model <: Product](
  status: (Int, String, Int)
)(
  val modelCl: Class[Model],
  val modelConditionFactory: ModelConditionFactory[Model],
  depFactory: DepFactory
) extends DepFilterWrapperPrePartApi[Model] {
  def createWith(
    leaf: LeafInfoHolder[Model, _ <: Product, _],
    newDep: String => Dep[Condition[Model]]
  ): DepFilterWrapperPartApi[Model] =
    DepFilterWrapperPartImpl(
      leaf :: Nil
    )(
      Seq(newDep),
      modelCl,
      modelConditionFactory,
      depFactory
    )
}

case class DepFilterWrapperPartImpl[Model <: Product](
  leafs: List[LeafInfoHolder[Model, _ <: Product, _]]
)(
  val depConditionSeq: Seq[String => Dep[Condition[Model]]],
  val modelCl: Class[Model],
  val modelConditionFactory: ModelConditionFactory[Model],
  depFactory: DepFactory
) extends DepFilterWrapperPartApi[Model] {

  def createWith(
    leaf: LeafInfoHolder[Model, _ <: Product, _],
    newDep: String => Dep[Condition[Model]]
  ): DepFilterWrapperPartApi[Model] =
    DepFilterWrapperPartImpl(
      leaf :: leafs
    )(newDep +: depConditionSeq,
      modelCl,
      modelConditionFactory,
      depFactory
    )

  def depCondition: String => Dep[Condition[Model]] = pk =>
    for {
      conditions <- depFactory.parallelSeq(depConditionSeq.map(_.apply(pk)))
    } yield {
      conditions.reduce(modelConditionFactory.intersect)
    }
}

trait DepFilterWrapper[Model <: Product] extends Product {
  def add[By <: Product, Field](
    byDep: String => Dep[Access[By]],
    lens: ProdGetter[Model, Field],
    byOptions: List[AbstractMetaAttr] = Nil
  )(
    implicit checker: ConditionCheckWithCl[By, Field]
  ): DepFilterWrapper[Model]

  /*
    If true, then first is selected, otherwise second
   */
  def addSwitch(
    isLeftDep: String => Dep[Boolean],
    partLeft: DepFilterWrapperPartApi[Model],
    partRight: DepFilterWrapperPartApi[Model]
  ): DepFilterWrapper[Model]

  def getPart: DepFilterWrapperPrePartApi[Model]

  def getLeafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]]

  def filterRequests: HashSearchDepRequestFactoryCreator => List[FLRequestDef]

  def modelCl: Class[Model]

  def listName: String
}

trait DepFilterWrapperPrePartApi[Model <: Product] extends DepFilterWrapperPartAdd[Model]

trait DepFilterWrapperPartApi[Model <: Product] extends DepFilterWrapperPartAdd[Model] {
  def leafs: List[LeafInfoHolder[Model, _ <: Product, _]]

  def depCondition: String => Dep[Condition[Model]]
}

trait DepFilterWrapperPartAdd[Model <: Product] {
  def modelCl: Class[Model]

  def modelConditionFactory: ModelConditionFactory[Model]

  def createWith(leaf: LeafInfoHolder[Model, _ <: Product, _], newDep: String => Dep[Condition[Model]]): DepFilterWrapperPartApi[Model]

  def add[By <: Product, Field](
    byDep: String => Dep[Option[Access[By]]],
    lens: ProdGetter[Model, Field],
    byOptions: List[AbstractMetaAttr] = Nil
  )(
    implicit checker: ConditionCheckWithCl[By, Field]
  ): DepFilterWrapperPartApi[Model] = {
    val (sByCl, sFieldCl) = (checker.byCl, checker.fieldCl)
    val newLeaf = LeafInfoHolder(lens, byOptions, checker, modelCl, sByCl, sFieldCl)
    val newDep: String => Dep[Condition[Model]] = pk =>
      for {
        byResolved <- byDep(pk)
      } yield {
        modelConditionFactory.leaf[By, Field](lens, byResolved.get.initialValue, byOptions)(checker)
      }
    createWith(newLeaf, newDep)
  }
}