package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypeContainer._
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

trait DepAssembleApp extends RequestHandlerRegistryApp with PreHashingApp with AssemblesApp{
  def qAdapterRegistry: QAdapterRegistry

  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry, qAdapterRegistry, preHashing) :: super.assembles
}

// TODO add unresolvedDepAssemble
@assemble class DepAssemble(
  handlerRegistry: RequestHandlerRegistry,
  val qAdapterRegistry: QAdapterRegistry,
  preHashing: PreHashing
) extends Assemble with DepAssembleUtilityImpl {
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
      resolvable ← handlerRegistry.handleAndBuildContext(request.innerRequest, responses)
    } yield {
      val upRes = DepResolvable(request, preHashing.wrap(resolvable))
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
        DepOuterResponse(rq, preHashing.wrap(resv.value)) :: Nil
      }
    } yield {
      WithPK(resp)
    }

  def DepOuterDamToDepInnerRequest
  (
    outerRqId: SrcId,
    @by[OuterRqByInnerSrcId] outers: Values[DepOuterRequest]
  ): Values[(SrcId, DepInnerRequest)] = {
    val inner: DepInnerRequest = Single(outers.map(_.innerRequest).distinct)
    WithPK(inner) :: Nil
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
      val outerResp = DepOuterResponse(outer, inner.valueHashed)
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
