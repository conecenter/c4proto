package ee.cone.c4actor.dep

import ee.cone.c4actor.{QAdapterRegistry, RichDataApp, WithPK}
import ee.cone.c4actor.dep.CtxType.Ctx
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.{InnerDep, UnresolvedDep}
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}

trait DepAssembleApp extends RqHandlerRegistryImplApp with RichDataApp {
  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry, qAdapterRegistry) :: super.assembles
}

@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, adapterRegistry: QAdapterRegistry) extends Assemble with DepAssembleUtilityImpl {

  type ToResponse = SrcId

  def RequestToUpResolvable
  (
    key: SrcId,
    @was requests: Values[RequestWithSrcId],
    @was @by[ToResponse] responses: Values[Response]
  ): Values[(SrcId, UpResolvable)] =
    for {
      request ← requests
      pair ← handlerRegistry.handle(request.request)
    } yield {
      val (dep, contextId) = pair
      val ctx: Ctx = handlerRegistry.buildContext(responses)(contextId)
      //println()
      //println(s"$key:$ctx")
      WithPK(UpResolvable(request, dep.asInstanceOf[InnerDep[_]].resolve(ctx)))
    }

  def UpResolvableToRequest
  (
    key: SrcId,
    resolvable: Values[UpResolvable]
  ): Values[(SrcId, RequestWithSrcId)] =
    for {
      rs ← resolvable
      rq ← rs.resolvable.requests
      if !rq.isInstanceOf[ContextIdRequest]
    } yield {
      //println()
      //println(s"URTRQ $key:${rs.resolvable.requests}")
      val id = generatePK(rq, adapterRegistry)
      WithPK(RequestWithSrcId(id, rq).addParent(rs.request.srcId))
    }

  def UpResolvableToResponses
  (
    key: SrcId,
    upResolvable: Values[UpResolvable]
  ): Values[(ToResponse, Response)] =
    upResolvable.flatMap { upRes ⇒
      //println()
      val response = Response(upRes.request, upRes.resolvable.value, upRes.request.parentSrcIds)
      //println(s"Resp: $response")
      WithPK(response) ::
        (for (srcId ← response.rqList) yield (srcId, response))
    }

  def UnresolvedDepCollector
  (
    key: SrcId,
    @was requests: Values[RequestWithSrcId],
    @was resolvables: Values[UpResolvable]
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
