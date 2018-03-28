package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.CtxType._
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4actor.dep.request.DepOuterDamToDepInnerRequestApp
import ee.cone.c4actor.{QAdapterRegistry, RichDataApp, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

trait DepAssembleApp extends RqHandlerRegistryImplApp with RichDataApp with DepOuterDamToDepInnerRequestApp {

  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry, qAdapterRegistry) :: super.assembles
}

// TODO add unresolvedDepAssemble
@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, val qAdapterRegistry: QAdapterRegistry) extends Assemble with DepAssembleUtilityImpl {
  type OuterRespForCtx = SrcId
  type DepResolvableGate = SrcId
  type OuterRqByInnerSrcId = SrcId

  def ResolveRequestWithCtx
  (
    requestId: SrcId,
    requests: Values[DepOuterRequest],
    @by[OuterRespForCtx] responses: Values[DepOuterResponse]
  ): Values[(DepResolvableGate, DepResolvable)] =
    for {
      request ← requests
      pair ← handlerRegistry.handle(request.innerRequest.request)
    } yield {
      val (dep, contextId) = pair
      val ctx: DepCtx = handlerRegistry.buildContext(responses)(contextId)
      val resolvable = dep.asInstanceOf[InnerDep[_]].resolve(ctx)
      val upRes = DepResolvable(request, resolvable)
      WithPK(upRes)
    }

  def DepResolvableGate
  (
    depResId: SrcId,
    @was @by[DepResolvableGate] depResolvables: Values[DepResolvable]
  ): Values[(SrcId, DepResolvable)] =
    for {
      depResolvable ← depResolvables
    } yield {
      WithPK(depResolvable)
    }


  // Produces Requests for the system
  def DepResolvableToDepOuterRequest
  (
    depResId: SrcId,
    depResolvables: Values[DepResolvable]
  ): Values[(SrcId, DepOuterRequest)] =
    for {
      depResolvable ← depResolvables
      rq ← depResolvable.resolvable.requests
      if !rq.isInstanceOf[ContextIdRequest]
    } yield {
      val outer = generateDepOuterRequest(rq, depResolvable.request.srcId)
      WithPK(outer)
    }

  def DepOuterRequestToDepOuterDam
  (
    outerRqId: SrcId,
    outerRqs: Values[DepOuterRequest]
  ): Values[(OuterRqByInnerSrcId, DepOuterRequest)] =
    for {
      rq ← outerRqs
    } yield {
      rq.innerRequest.srcId → rq
    }

  // end: Produces Requests for the system

  // Produces Responses for the system
  // from DepResolvable
  def DepResolvableToDepOuterResponse
  (
    depResId: SrcId,
    depResolvables: Values[DepResolvable]
  ): Values[(SrcId, DepOuterResponse)] =
    for {
      depResv ← depResolvables
      resp ← {
        val rq = depResv.request
        val resv = depResv.resolvable
        DepOuterResponse(rq, resv.value) :: Nil
      }
    } yield {
      WithPK(resp)
    }

  // from DepInnerResponse and DepOuterRequest
  def DepInnerResponseToDepOuterResponse
  (
    innerRespId: SrcId,
    depInnerReps: Values[DepInnerResponse],
    @by[OuterRqByInnerSrcId] outerRqs: Values[DepOuterRequest]
  ): Values[(SrcId, DepOuterResponse)] =
    for {
      inner ← depInnerReps
      outer ← outerRqs
    } yield {
      val outerResp = DepOuterResponse(outer, inner.value)
      WithPK(outerResp)
    }

  // move DepOuterRespTo CtxBuilder index
  def DepOuterResponseToCtxBuilder
  (
    depOuterRespId: SrcId,
    depOuterResps: Values[DepOuterResponse]
  ): Values[(OuterRespForCtx, DepOuterResponse)] =
    for {
      resp ← depOuterResps
      srcId ← resp.request.srcId :: resp.request.parentSrcId :: Nil
    } yield {
      srcId → resp
    }

  // end: Produces Responses for the system

}
