package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.CtxType.DepCtx
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4actor.{QAdapterRegistry, RichDataApp, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}

trait DepAssembleApp extends RqHandlerRegistryImplApp with RichDataApp {
  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry, qAdapterRegistry) :: super.assembles
}

@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, adapterRegistry: QAdapterRegistry) extends Assemble with DepAssembleUtilityImpl {

  type ToResponse = SrcId
  type CtxSrcId = SrcId

  def BuildContext
  (
    key: SrcId,
    @was @by[ToResponse] responses: Values[DepResponse]
  ): Values[(CtxSrcId, DepCtxMap)] =
    WithPK(DepCtxMap(key, handlerRegistry.buildContextWoSession(responses))) :: Nil

  def GenRequestToUpResolvable
  (
    key: SrcId,
    @was requests: Values[DepRequestWithSrcId],
    @by[CtxSrcId] ctxs: Values[DepCtxMap]
  ): Values[(SrcId, UpResolvable)] =
    for {
      request ← requests
      ctxT ← ctxs
      pair ← handlerRegistry.handle(request.request)
    } yield {
      val (dep, contextId) = pair
      val ctx: DepCtx = ctxT.map + (ContextIdRequest() → Option(contextId))
      //println()
      //println(s"$key:$ctx")
      WithPK(UpResolvable(request, dep.asInstanceOf[InnerDep[_]].resolve(ctx)))
    }

  def GenUpResolvableToRequest
  (
    key: SrcId,
    resolvable: Values[UpResolvable]
  ): Values[(SrcId, DepRequestWithSrcId)] =
    for {
      rs ← resolvable
      rq ← rs.resolvable.requests
      if !rq.isInstanceOf[ContextIdRequest]
    } yield {
      //println()
      //println(s"URTRQ $key:${rs.resolvable.requests}")
      val id = generatePK(rq, adapterRegistry)
      WithPK(DepRequestWithSrcId(id, rq).addParent(rs.request.srcId))
    }

  def GenUpResolvableToResponses
  (
    key: SrcId,
    upResolvable: Values[UpResolvable]
  ): Values[(ToResponse, DepResponse)] =
    upResolvable.flatMap { upRes ⇒
      //println()
      val response = DepResponse(upRes.request, upRes.resolvable.value, upRes.request.parentSrcIds)
      //println(s"Resp: $response")
      WithPK(response) ::
        (for (srcId ← response.rqList) yield (srcId, response))
    }

  def GenUnresolvedDepCollector
  (
    key: SrcId,
    @was requests: Values[DepRequestWithSrcId],
    resolvables: Values[UpResolvable]
  ): Values[(SrcId, UnresolvedDep)] =
    for {
      rq ← requests
      resv ← resolvables
      if resv.resolvable.value.isEmpty
    } yield {
      //println(s"UnRes $rq:${resv.resolvable}")
      WithPK(UnresolvedDep(rq, resv))
    }
}
