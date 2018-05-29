package ee.cone.c4actor.dep_impl

import scala.collection.immutable.Seq
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest, GroupId}
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.ToByteString

case class DepResponseImpl(innerRequest: DepInnerRequest, valueHashed: PreHashed[Option[_]]) extends DepResponse {
  def value: Option[_] = valueHashed.value
}
case class DepInnerResolvable(result: DepResponse, subRequests: Seq[(SrcId,DepOuterRequest)])

@assemble class DepAssemble(reg: DepRequestHandlerRegistry) extends Assemble {

  def resolvableToSubRequests(
    innerId: SrcId,
    @was resolvableSeq: Values[DepInnerResolvable]
  ): Values[(GroupId, DepOuterRequest)] = for {
    resolvable ← resolvableSeq
    pair ← resolvable.subRequests
  } yield pair

  def toSingleInnerRequest(
    innerId: SrcId,
    @by[GroupId] outerRequests: Values[DepOuterRequest]
  ): Values[(SrcId,DepInnerRequest)] =
    Seq(WithPK(Single(outerRequests.map(_.innerRequest).distinct)))

  def resolvableToResponse(
    innerId: SrcId,
    @was resolvableSeq: Values[DepInnerResolvable]
  ): Values[(SrcId, DepResponse)] = for {
    resolvable ← resolvableSeq
  } yield WithPK(resolvable.result)

  def responseToParent(
    innerId: SrcId,
    responses: Values[DepResponse],
    @by[GroupId] outerRequests: Values[DepOuterRequest]
  ): Values[(GroupId, DepResponse)] = for {
    response ← responses
    outer ← outerRequests
  } yield outer.parentSrcId → response

  def handle(
    innerId: SrcId,
    innerRequests: Values[DepInnerRequest],
    @by[GroupId] responses: Values[DepResponse]
  ): Values[(SrcId, DepInnerResolvable)] = for {
    req ← innerRequests
    handle ← reg.handle(req)
  } yield WithPK(handle(responses))
}

case class DepRequestHandlerRegistry(
  depOuterRequestFactory: DepOuterRequestFactory,
  depResponseFactory: DepResponseFactory,
  handlerSeq: Seq[DepHandler]
)(
  handlers: Map[String,DepHandler] = CheckedMap(handlerSeq.map(h⇒h.className→h))
) {
  def handle(req: DepInnerRequest): Option[Values[DepResponse]⇒DepInnerResolvable] =
    handlers.get(req.request.getClass.getName).map{ (handler:DepHandler) ⇒ (responses:Values[DepResponse]) ⇒
      val ctx: DepCtx = (for {
        response ← responses
        value ← response.value
      } yield response.innerRequest.request → value).toMap
      val resolvable: Resolvable[_] = handler.handle(req.request)(ctx)
      val response = depResponseFactory.wrap(req,resolvable.value)
      DepInnerResolvable(response, resolvable.requests.map(depOuterRequestFactory.pair(req.srcId)))
    }
}

case class DepResponseFactoryImpl()(preHashing: PreHashing) extends DepResponseFactory {
  def wrap(req: DepInnerRequest, value: Option[_]): DepResponse =
    DepResponseImpl(req,preHashing.wrap(value))
}

case class DepOuterRequestFactoryImpl(uuidUtil: UUIDUtil)(qAdapterRegistry: QAdapterRegistry) extends DepOuterRequestFactory {
  def pair(parentId: SrcId)(rq: DepRequest): (SrcId,DepOuterRequest) = {
    val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(rq))
    val innerId = uuidUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    val inner = DepInnerRequest(innerId, rq)
    val outerId = uuidUtil.srcIdFromSrcIds(parentId, inner.srcId)
    inner.srcId → DepOuterRequest(outerId, inner, parentId)
  }
}


// ContextId: ContextIdRequest() →
