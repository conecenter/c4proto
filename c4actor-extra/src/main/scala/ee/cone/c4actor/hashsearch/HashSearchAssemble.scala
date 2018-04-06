package ee.cone.c4actor.hashsearch

import ee.cone.c4actor._
import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepAssembleUtilityImpl
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

case class ConditionOuter[Model <: Product](srcId: SrcId, conditionInner: ConditionInner[Model], parentSrcId: SrcId, requestId: SrcId)

case class ConditionInner[Model <: Product](srcId: SrcId, condition: SerializableCondition[Model])

case class CountEstimate[Model <: Product](srcId: SrcId, count: Int, heapIds: List[SrcId])

trait HashSearchAssembleSharedKeys {
  // Shared keys
  type SharedHeapId = SrcId
  type ResponseId = SrcId
}

@assemble class HashSearchAssemble[Model <: Product](
  modelCl: Class[Model],
  qAdapterRegistry: QAdapterRegistry
) extends Assemble with HashSearchAssembleSharedKeys
  with DepAssembleUtilityImpl {
  type CondCountId = SrcId
  type CondInnerChildId = SrcId
  type CondInnerId = SrcId
  type RootCondInnerId = SrcId

  // Parse condition
  def RequestToConditions(
    requestId: SrcId,
    requests: Values[Request[Model]]
  ): Values[(SrcId, ConditionOuter[Model])] =
    for {
      request ← requests
    } yield {
      val condition = request.condition.asInstanceOf[SerializableCondition[Model]]
      val condId = condition.getPK(modelCl)(qAdapterRegistry)
      WithPK(ConditionOuter(request.requestId, ConditionInner(condId, condition), "", request.requestId))
    }

  def LinkRootOuterWithRootInner(
    outerRootId: SrcId,
    outerRoots: Values[ConditionOuter[Model]]
  ): Values[(RootCondInnerId, ConditionOuter[Model])] =
    for {
      outerRoot ← outerRoots
      if outerRoot.parentSrcId.nonEmpty && outerRoot.parentSrcId.isEmpty
    } yield {
      outerRoot.conditionInner.srcId → outerRoot
    }

  // Outpoint for leafs
  def ConditionOuterToInner(
    condOuterId: SrcId,
    @was condOuters: Values[ConditionOuter[Model]]
  ): Values[(SrcId, ConditionInner[Model])] =
    for {
      condOuter ← condOuters
    } yield WithPK(condOuter.conditionInner)

  def ConditionInnerToChildren(
    condInnerId: SrcId,
    condInners: Values[ConditionInner[Model]]
  ): Values[(CondInnerChildId, ConditionInner[Model])] =
    for {
      condInner ← condInners
      lrCond ← {
        condInner.condition match {
          case IntersectCondition(left, right) ⇒ left :: right :: Nil
          case UnionCondition(left, right) ⇒ left :: right :: Nil
          case _ ⇒ Nil
        }
      }
    } yield {
      val condId = lrCond.getPK(modelCl)(qAdapterRegistry)
      condId → condInner
    }

  def ConditionOuterToInnerId(
    condOuterId: SrcId,
    condOuters: Values[ConditionOuter[Model]]
  ): Values[(CondInnerId, ConditionOuter[Model])] =
    for {
      condOuter ← condOuters
    } yield condOuter.conditionInner.srcId → condOuter

  def ParseConditionUnionInter(
    conditionId: SrcId,
    conditionInners: Values[ConditionInner[Model]],
    @by[CondInnerId] condOuters: Values[ConditionOuter[Model]]
  ): Values[(SrcId, ConditionOuter[Model])] =
    for {
      condInner ← conditionInners
      lrCond ← {
        condInner.condition match {
          case IntersectCondition(left, right) ⇒ left :: right :: Nil
          case UnionCondition(left, right) ⇒ left :: right :: Nil
          case _ ⇒ Nil
        }
      }
      parentId ← condOuters.map(_.srcId)
    } yield {
      val condId = lrCond.getPK(modelCl)(qAdapterRegistry)
      val condInner = ConditionInner(condId, lrCond)
      WithPK(ConditionOuter(generatePKFromTwoSrcId(condId, parentId), condInner, parentId, ""))
    }

  // end parseCondition


  // Parse count response
  def ReceiveCountEstimate(
    countEstimateId: SrcId,
    countEstimates: Values[CountEstimate[Model]],
    condInners: Values[ConditionInner[Model]]
  ): Values[(CondCountId, CountEstimate[Model])] =
    for {
      countEstimate ← countEstimates
      condInner ← condInners
    } yield {
      condInner.srcId → countEstimate
    }

  def ContEstimateToParents(
    countEstId: SrcId,
    @was @by[CondCountId] countEstimates: Values[CountEstimate[Model]],
    condInners: Values[ConditionInner[Model]],
    @by[CondInnerChildId] parentCondInners: Values[ConditionInner[Model]]
  ): Values[(CondCountId, CountEstimate[Model])] =
    for {
      condInner ← condInners
      result ← condInner.condition match {
        case IntersectCondition(_, _) ⇒ (countEstimates.minBy(_.count) match {
          case CountEstimate(_, count, list) => (count, list)
        }) :: Nil
        case UnionCondition(_, _) ⇒ countEstimates.foldLeft[(Int, List[SrcId])]((0, Nil))((z, model) ⇒ {
          val (count, list) = z
          (count + model.count, model.heapIds ::: list)
        }
        ) :: Nil
        case _ ⇒ Nil
      }
      parent ← parentCondInners
    } yield {
      val (count, list) = result
      parent.srcId → CountEstimate[Model](condInner.srcId, count, list)
    }

  def RequestToHeapsByCount(
    requestId: SrcId,
    @by[RootCondInnerId] outerRoots: Values[ConditionOuter[Model]],
    innersRoots: Values[ConditionInner[Model]],
    @by[CondCountId] condCounts: Values[CountEstimate[Model]]
  ): Values[(RootCondInnerId, CountEstimate[Model])] =
    for {
      outerRoot ← outerRoots
      innerRoot ← innersRoots
      countEst ← condCounts
    } yield {
      outerRoot.requestId → countEst
    }

  def RequestToHeaps(
    requestId: SrcId,
    requests: Values[Request[Model]],
    @by[RootCondInnerId] counts: Values[CountEstimate[Model]]
  ): Values[(SharedHeapId, Request[Model])] =
    for {
      request ← requests
      count ← counts
      heapId ← count.heapIds
    } yield {
      heapId → request
    }

  def ResponseByRequest(
    requestId: SrcId,
    requests: Values[Request[Model]],
    @by[ResponseId] responses: Values[Model]
  ): Values[(SrcId, Response[Model])] =
    for {
      request ← requests
    } yield {
      val pk = ToPrimaryKey(request)
      pk → Response(pk, request, responses.toList.distinct)
    }

}
