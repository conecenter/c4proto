package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepAssembleUtilityImpl
import ee.cone.c4actor.hashsearch.condition.{SerializationUtils, SerializationUtilsApp}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

trait OuterConditionApi[Model <: Product] extends Product {
  def srcId: SrcId

  def conditionInner: InnerCondition[Model]
}

case class RootCondition[Model <: Product](srcId: SrcId, conditionInner: InnerCondition[Model], requestId: SrcId) extends OuterConditionApi[Model]

case class RootInnerCondition[Model <: Product](srcId: SrcId, condition: Condition[Model])

case class OuterCondition[Model <: Product](srcId: SrcId, conditionInner: InnerCondition[Model], parentInnerId: SrcId) extends OuterConditionApi[Model]

case class InnerCondition[Model <: Product](srcId: SrcId, condition: Condition[Model])

case class ResolvableCondition[Model <: Product](outerCondition: OuterConditionApi[Model], childInnerConditions: List[InnerCondition[Model]])

case class InnerConditionEstimate[Model <: Product](conditionInner: InnerCondition[Model], count: Int, heapIds: List[SrcId])

case class SrcIdContainer[Model <: Product](srcId: SrcId, modelCl: Class[Model])

case class ResponseModelList[Model <: Product](srcId: SrcId, modelList: Values[Model]) extends LazyHashCodeProduct

trait HashSearchAssembleSharedKeys {
  // Shared keys
  type SharedHeapId = SrcId
  type SharedResponseId = SrcId
}

trait HashSearchModelsApp {
  def hashSearchModels: List[Class[_ <: Product]] = Nil
}

trait HashSearchAssembleApp extends AssemblesApp with HashSearchModelsApp with SerializationUtilsApp {
  def qAdapterRegistry: QAdapterRegistry

  override def assembles: List[Assemble] = hashSearchModels.distinct.map(new HashSearchAssemble(_, qAdapterRegistry, serializer)) ::: super.assembles
}

object HashSearchAssembleUtils {
  def parseCondition[Model <: Product]: Condition[Model] ⇒ List[Condition[Model]] = {
    case IntersectCondition(left, right) ⇒ left :: right :: Nil
    case UnionCondition(left, right) ⇒ left :: right :: Nil
    case _ ⇒ Nil
  }

  def bestEstimate[Model <: Product]: InnerCondition[Model] ⇒ Values[InnerConditionEstimate[Model]] ⇒ Option[InnerConditionEstimate[Model]] =
    inner ⇒ estimates ⇒ {
      val distinctEstimates = estimates.distinct
      inner.condition match {
        case _: IntersectCondition[Model] ⇒ Utility.minByOpt(distinctEstimates)(_.count).map(_.copy(conditionInner = inner))
        case _: UnionCondition[Model] ⇒
          import scala.util.control.Exception._
          allCatch.opt(
            distinctEstimates.reduce[InnerConditionEstimate[Model]](
              (a, b) ⇒ a.copy(count = a.count + b.count, heapIds = a.heapIds ::: b.heapIds)
            ).copy(conditionInner = inner)
          )
        case _ ⇒ None
      }
    }
}

import ee.cone.c4actor.hashsearch.base.HashSearchAssembleUtils._

@assemble class HashSearchAssemble[Model <: Product](
  modelCl: Class[Model],
  val qAdapterRegistry: QAdapterRegistry,
  condSer: SerializationUtils
) extends Assemble with HashSearchAssembleSharedKeys
  with DepAssembleUtilityImpl {
  type CondInnerId = SrcId
  type RootCondInnerId = SrcId
  type RootRequestId = SrcId
  type ResCondDamn = SrcId
  type OuterParentEstimate = SrcId
  type InnerParentEstimate = SrcId

  // Parse condition
  def RequestToRootCondition(
    requestId: SrcId,
    requests: Values[Request[Model]]
  ): Values[(SrcId, RootCondition[Model])] =
    for {
      request ← requests
    } yield {
      val condition = request.condition
      val condId = condSer.getConditionPK(modelCl, condition)
      WithPK(RootCondition(condSer.srcIdFromSrcIds(request.requestId, condId), InnerCondition(condId, condition), request.requestId))
    }

  def RootCondToInnerCondition(
    rootCondId: SrcId,
    rootConditions: Values[RootCondition[Model]]
  ): Values[(RootCondInnerId, RootCondition[Model])] =
    for {
      rootCond ← rootConditions
    } yield rootCond.conditionInner.srcId → rootCond

  def RootCondIntoInnerCondition(
    rootCondId: SrcId,
    @by[RootCondInnerId] rootConditions: Values[RootCondition[Model]]
  ): Values[(SrcId, InnerCondition[Model])] = {
    val inner = Single(rootConditions.map(_.conditionInner).distinct)
    WithPK(inner) :: Nil
  }

  def RootCondIntoRootInnerCondition(
    rootInnerId: SrcId,
    @by[RootCondInnerId] rootConditions: Values[RootCondition[Model]]
  ): Values[(SrcId, RootInnerCondition[Model])] = {
    val inner = Single(rootConditions.map(_.conditionInner).distinct)
    WithPK(RootInnerCondition(inner.srcId, inner.condition)) :: Nil
  }

  def RootCondToResolvableCond(
    rootCondId: SrcId,
    rootConditions: Values[RootCondition[Model]]
  ): Values[(ResCondDamn, ResolvableCondition[Model])] =
    for {
      rootCond ← rootConditions
    } yield {
      val children = parseCondition(rootCond.conditionInner.condition).map(cond ⇒ InnerCondition(condSer.getConditionPK(modelCl, cond), cond))
      WithPK(ResolvableCondition(rootCond, children))
    }

  def OuterCondToResolvableCond(
    outerCondId: SrcId,
    outerConditions: Values[OuterCondition[Model]]
  ): Values[(ResCondDamn, ResolvableCondition[Model])] =
    for {
      outerCond ← outerConditions
    } yield {
      val children = parseCondition(outerCond.conditionInner.condition).map(cond ⇒ InnerCondition(condSer.getConditionPK(modelCl, cond), cond))
      WithPK(ResolvableCondition(outerCond, children))
    }

  def ResolvableConditionDamn(
    resId: SrcId,
    @was @by[ResCondDamn] resConds: Values[ResolvableCondition[Model]]
  ): Values[(SrcId, ResolvableCondition[Model])] =
    for {
      res ← resConds
    } yield WithPK(res)

  def ResolvableCondToOuters(
    resId: SrcId,
    resConditions: Values[ResolvableCondition[Model]]
  ): Values[(SrcId, OuterCondition[Model])] =
    for {
      res ← resConditions
      inner ← res.childInnerConditions
    } yield {
      val parentId = ToPrimaryKey(res)
      WithPK(OuterCondition(generatePKFromTwoSrcId(parentId, inner.srcId), inner, res.outerCondition.conditionInner.srcId))
    }

  def OuterConditionToInnerId(
    outerId: SrcId,
    outerConditions: Values[OuterCondition[Model]]
  ): Values[(CondInnerId, OuterCondition[Model])] =
    for {
      outer ← outerConditions
    } yield outer.conditionInner.srcId → outer

  def ConditionOuterToInner(
    condOuterId: SrcId,
    @by[CondInnerId] condOuters: Values[OuterCondition[Model]]
  ): Values[(SrcId, InnerCondition[Model])] = {
    val inner: InnerCondition[Model] = Single(condOuters.map(_.conditionInner).distinct)
    WithPK(inner) :: Nil
  }

  // end parseCondition


  // Parse count response
  def LeafInnerCondEstimateToOuterParent(
    condEstimateId: SrcId,
    @was condEstimates: Values[InnerConditionEstimate[Model]],
    @by[CondInnerId] condOuters: Values[OuterCondition[Model]]
  ): Values[(InnerParentEstimate, InnerConditionEstimate[Model])] =
    for {
      estimate ← condEstimates
      outer ← condOuters
    } yield {
      outer.parentInnerId → estimate
    }

  def InnerEstimateParseWithInnerCond(
    innerCondId: SrcId,
    @by[InnerParentEstimate] estimates: Values[InnerConditionEstimate[Model]],
    innerConditions: Values[InnerCondition[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] =
    for {
      inner ← innerConditions
      bestEstimate ← bestEstimate(inner)(estimates)
    } yield {
      WithPK(bestEstimate)
    }

  def CountEstimateNRootInnerToHeaps(
    requestId: SrcId,
    rootInnerConds: Values[RootInnerCondition[Model]],
    condEstimates: Values[InnerConditionEstimate[Model]]
  ): Values[(SharedHeapId, RootInnerCondition[Model])] =
    for {
      rootInner ← rootInnerConds
      estimate ← Single.option(condEstimates).toList
      heapId ← estimate.heapIds
    } yield heapId → rootInner


  type RequestId = SrcId

  def ResponsesToRequest(
    rootInnerId: SrcId,
    rootInnerConds: Values[RootInnerCondition[Model]],
    @by[SharedResponseId] responses: Values[ResponseModelList[Model]],
    @by[RootCondInnerId] rootConditions: Values[RootCondition[Model]]
  ): Values[(RequestId, ResponseModelList[Model])] = {
    val finalList = responses.flatMap(_.modelList)
    val distinctList = DistinctBySrcIdGit(finalList)
    //val time = System.currentTimeMillis()
    for {
      root ← rootConditions
    } yield {
      WithPK(ResponseModelList(root.requestId, distinctList))
      /*val time2 = System.currentTimeMillis()-time
    if (time2 > 0)
      println("{TIME2-git}", time2)*/
    }
  }

  def ResponseByRequest(
    requestId: SrcId,
    requests: Values[Request[Model]],
    @by[RequestId] responses: Values[ResponseModelList[Model]]
  ): Values[(SrcId, Response[Model])] =
    for {
      request ← requests
    } yield {
      val pk = ToPrimaryKey(request)
      WithPK(Response(pk, request, Single.option(responses).map(_.modelList).toList.flatten))
    }

}
