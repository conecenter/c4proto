package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.DepAssembleUtilityImpl
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

trait OuterConditionApi[Model <: Product] extends Product {
  def srcId: SrcId

  def conditionInner: InnerCondition[Model]
}

case class RootCondition[Model <: Product](srcId: SrcId, conditionInner: InnerCondition[Model], requestId: SrcId) extends OuterConditionApi[Model]

case class OuterCondition[Model <: Product](srcId: SrcId, conditionInner: InnerCondition[Model], parentInnerId: SrcId) extends OuterConditionApi[Model]

case class InnerCondition[Model <: Product](srcId: SrcId, condition: SerializableCondition[Model])

case class ResolvableCondition[Model <: Product](outerCondition: OuterConditionApi[Model], childInnerConditions: List[InnerCondition[Model]])

case class InnerConditionEstimate[Model <: Product](conditionInner: InnerCondition[Model], count: Int, heapIds: List[SrcId])

trait HashSearchAssembleSharedKeys {
  // Shared keys
  type SharedHeapId = SrcId
  type SharedResponseId = SrcId
}

trait HashSearchModelsApp {
  def hashSearchModels: List[Class[_ <: Product]] = Nil
}

trait HashSearchAssembleApp extends AssemblesApp with HashSearchModelsApp {
  def qAdapterRegistry: QAdapterRegistry

  override def assembles: List[Assemble] = hashSearchModels.distinct.map(new HashSearchAssemble(_, qAdapterRegistry)) ::: super.assembles
}

object HashSearchAssembleUtils {
  def parseCondition[Model <: Product]: SerializableCondition[Model] ⇒ List[SerializableCondition[Model]] = cond ⇒ {
    cond match {
      case IntersectCondition(left, right) ⇒ left :: right :: Nil
      case UnionCondition(left, right) ⇒ left :: right :: Nil
      case _ ⇒ Nil
    }
  }.asInstanceOf[List[SerializableCondition[Model]]]

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
  val qAdapterRegistry: QAdapterRegistry
) extends Assemble with HashSearchAssembleSharedKeys
  with DepAssembleUtilityImpl {
  type CondInnerId = SrcId
  type RootCondInnerId = SrcId
  type RoorRequestId = SrcId
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
      val condition = request.condition.asInstanceOf[SerializableCondition[Model]]
      val condId = condition.getPK(modelCl)(qAdapterRegistry)
      WithPK(RootCondition(generatePKFromTwoSrcId(request.requestId, condId), InnerCondition(condId, condition), request.requestId))
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
    val inner = Single(rootConditions.map(_.conditionInner))
    WithPK(inner) :: Nil
  }

  def RootCondToResolvableCond(
    rootCondId: SrcId,
    rootConditions: Values[RootCondition[Model]]
  ): Values[(ResCondDamn, ResolvableCondition[Model])] =
    for {
      rootCond ← rootConditions
    } yield {
      val children = parseCondition(rootCond.conditionInner.condition).map(cond ⇒ InnerCondition(cond.getPK(modelCl)(qAdapterRegistry), cond))
      WithPK(ResolvableCondition(rootCond, children))
    }

  def OuterCondToResolvableCond(
    outerCondId: SrcId,
    outerConditions: Values[OuterCondition[Model]]
  ): Values[(ResCondDamn, ResolvableCondition[Model])] =
    for {
      outerCond ← outerConditions
    } yield {
      val children = parseCondition(outerCond.conditionInner.condition).map(cond ⇒ InnerCondition(cond.getPK(modelCl)(qAdapterRegistry), cond))
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

  /*def InnerEstimateToParentInner(
    condEstimateId: SrcId,
    @by[OuterParentEstimate] estimates: Values[InnerConditionEstimate[Model]],
    outers: Values[OuterCondition[Model]]
  ): Values[(InnerParentEstimate, InnerConditionEstimate[Model])] =
    for {
      outer ← outers
      estimate ← estimates
    } yield {
      println(outer.conditionInner, estimate)
      outer.conditionInner.srcId → estimate}*/

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

  def CountEstimateToRequest(
    requestId: SrcId,
    @by[RootCondInnerId] rootConditions: Values[RootCondition[Model]],
    condEstimates: Values[InnerConditionEstimate[Model]]
  ): Values[(RoorRequestId, InnerConditionEstimate[Model])] =
    for {
      rootCond ← rootConditions
      estimate ← condEstimates
    } yield rootCond.requestId → estimate

  def RequestToHeaps(
    requestId: SrcId,
    requests: Values[Request[Model]],
    @by[RoorRequestId] counts: Values[InnerConditionEstimate[Model]]
  ): Values[(SharedHeapId, Request[Model])] =
    for {
      request ← requests
      count ← Single.option(counts).toList
      heapId ← count.heapIds
    } yield {
      heapId → request
    }

  def ResponseByRequest(
    requestId: SrcId,
    requests: Values[Request[Model]],
    @by[SharedResponseId] responses: Values[Model]
  ): Values[(SrcId, Response[Model])] =
    for {
      request ← requests
    } yield {
      val pk = ToPrimaryKey(request)
      pk → Response(pk, request, responses.toList.distinct)
    }

}
