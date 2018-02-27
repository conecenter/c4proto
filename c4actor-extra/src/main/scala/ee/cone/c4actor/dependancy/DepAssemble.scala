package ee.cone.c4actor.dependancy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

import ee.cone.c4actor.CtxType.{Ctx, Request}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{QAdapterRegistry, QAdapterRegistryFactory, RichDataApp, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import DepAssembleUtils._

trait DepAssembleApp extends RqHandlerRegistryImplApp with ByPKRequestHandlerApp with RichDataApp{
  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry, qAdapterRegistry) :: super.assembles
}

@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, adapterRegistry: QAdapterRegistry) extends Assemble {

  type ToResponse = SrcId

  def RequestToUpResolvable
  (
    key: SrcId,
    @was requests: Values[RequestWithSrcId],
    @was @by[ToResponse] responses: Values[Response]
  ): Values[(SrcId, UpResolvable)] =
    for (
      request ← requests;
      (dep,contextId) ← handlerRegistry.handle(request.request)
    ) yield {
      val ctx: Ctx = handlerRegistry.buildContext(responses, contextId)
      println()
      println(s"$key:$ctx")
      WithPK(UpResolvable(request, dep.asInstanceOf[InnerDep[_]].resolve(ctx)))
    }

  def UpResolvableToRequest
  (
    key: SrcId,
    resolvable: Values[UpResolvable]
  ): Values[(SrcId, RequestWithSrcId)] =
    for (
      rs ← resolvable;
      rq ← rs.resolvable.requests
    ) yield {
      println(s"$key:${rs.resolvable.requests}")
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
    for (
      rq ← requests;
      resv ← resolvables
      if resv.resolvable.value.isEmpty
    ) yield {
      //println(s"UnRes $rq:${resv.resolvable}")
      WithPK(UnresolvedDep(rq, resv))
    }
}

object DepAssembleUtils {
  def generatePK(rq : Request, adapterRegistry: QAdapterRegistry): SrcId = {
    val valueAdapter = adapterRegistry.byName(rq.getClass.getName)
    val bytes = valueAdapter.encode(rq)
    UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes).toString
  }

  private def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def stringToKey(value : String) =
    UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString
}
