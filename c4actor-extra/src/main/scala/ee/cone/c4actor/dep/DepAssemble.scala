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
    @by[ToResponse] responses: Values[DepResponse]
  ): Values[(CtxSrcId, DepCtxResponses)] = {
    //println(key, handlerRegistry.buildContextWoSession(responses))
    //println("--------------------------------------------------------------")
    //val prepedResponses: List[DepResponse] = responses.groupBy(_.request).toList.map(_._2).map(list ⇒ list.minBy(_.toString.length))
    for {
      resp ← responses
      srcId ← {
        resp.request.srcId :: resp.rqList
      }
    } yield
      srcId → DepCtxResponses(responses.head.request.srcId, responses.toList)
  }

  def GenRequestToUpResolvable
  (
    key: SrcId,
    requests: Values[DepRequestWithSrcId],
    @by[CtxSrcId] ctxs: Values[DepCtxResponses]
  ): Values[(SrcId, UpResolvable)] =
    for {
      request ← requests
      pair ← handlerRegistry.handle(request.request)
    } yield {
      val (dep, contextId) = pair
      val ctxT = handlerRegistry.buildContext(ctxs.headOption.map(_.ctx).getOrElse(Nil))(contextId)
      //val ctxT = ctxs.headOption.map(_.ctx).getOrElse(Map.empty)
      val ctx: DepCtx = ctxT
      //println()
      //println(s"$key:$ctx")
      WithPK(UpResolvable(request, dep.asInstanceOf[InnerDep[_]].resolve(ctx)))
    }

  type TempDepRQSrcId = SrcId
  def GenUpResolvableToRequest
  (
    key: SrcId,
    @was resolvable: Values[UpResolvable]
  ): Values[(TempDepRQSrcId, DepRqWithSrcId)] =
    for {
      rs ← resolvable
      rq ← rs.resolvable.requests
      if !rq.isInstanceOf[ContextIdRequest]
    } yield {
      //println()
      //println(s"URTRQ $key:${rs.resolvable.requests}")
      val id = generatePK(rq, adapterRegistry)
      WithPK(DepRqWithSrcId(id, rq))
    }

  type ParentBusSrcId = SrcId
  def GenUpResolvableToParentBus
  (
    key: SrcId,
    @was resolvable: Values[UpResolvable]
  ): Values[(ParentBusSrcId, ParentBus)] =
    for {
      rs ← resolvable
      rq ← rs.resolvable.requests
      if !rq.isInstanceOf[ContextIdRequest]
    } yield {
      //println()
      //println(s"URTRQ $key:${rs.resolvable.requests}")
      val id = generatePK(rq, adapterRegistry)
      WithPK(ParentBus(id, rs.request.srcId))
    }

  def MakeSharedDepRequest
  (
    key: SrcId,
    @by[TempDepRQSrcId] request: Values[DepRqWithSrcId],
    @by[ParentBusSrcId] parents: Values[ParentBus]
  ): Values[(SrcId, DepRequestWithSrcId)] =
    for {
      rq ← request
    } yield WithPK(DepRequestWithSrcId(rq.srcId, rq.request,parents.map(_.parentSrcIds).filter(p ⇒ p!= rq.srcId).toList))



  def GenUpResolvableToResponses
  (
    key: SrcId,
    @was upResolvable: Values[UpResolvable]
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
    requests: Values[DepRequestWithSrcId],
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
