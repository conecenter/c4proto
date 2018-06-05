package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest, GroupId}
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.ToByteString

import scala.collection.immutable.{Map, Seq}

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
  handlers: Map[String,(DepRequest,DepCtx)⇒Resolvable[_]] =
  handlerSeq.collect { case h: InternalDepHandler ⇒ h }.groupBy(_.requestClName)
      .transform { (k, handlers) ⇒
        val by = Single(handlers.collect { case h: ByDepHandlerApi[_] ⇒ h.handle.asInstanceOf[DepRequest ⇒ Dep[_]] })
        val adds = handlers.collect { case h: AddDepHandlerApi[_, _] ⇒ h.add.asInstanceOf[DepRequest ⇒ DepCtx] }
        (req: DepRequest, ctx: DepCtx) ⇒
        //by(req).resolve(adds.foldLeft(ctx)((acc:DepCtx,add)⇒acc ++ (add(req):DepCtx)))
        by(req).resolve(ctx ++ adds.flatMap(_(req)))
      }
) {
  def handle(req: DepInnerRequest): Option[Values[DepResponse]⇒DepInnerResolvable] =
    handlers.get(req.request.getClass.getName).map{ (handle:(DepRequest,DepCtx)⇒Resolvable[_]) ⇒ (responses:Values[DepResponse]) ⇒
      val ctx: DepCtx = (for {
        response ← responses
        value ← response.value
      } yield response.innerRequest.request → value).toMap
      val resolvable: Resolvable[_] = handle(req.request,ctx)
      val response = depResponseFactory.wrap(req,resolvable.value)
      DepInnerResolvable(response, resolvable.requests.map(depOuterRequestFactory.tupled(req.srcId)))
    }
}

case class DepResponseFactoryImpl()(preHashing: PreHashing) extends DepResponseFactory {
  def wrap(req: DepInnerRequest, value: Option[_]): DepResponse =
    DepResponseImpl(req,preHashing.wrap(value))
}

case class DepOuterRequestFactoryImpl(uuidUtil: UUIDUtil)(qAdapterRegistry: QAdapterRegistry) extends DepOuterRequestFactory {
  def tupled(parentId: SrcId)(rq: DepRequest): (SrcId,DepOuterRequest) = {
    val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(rq))
    val innerId = uuidUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    val inner = DepInnerRequest(innerId, rq)
    val outerId = uuidUtil.srcIdFromSrcIds(parentId, inner.srcId)
    inner.srcId → DepOuterRequest(outerId, inner, parentId)
  }
}

trait InternalDepHandler extends DepHandler {
  def rqCl: Class[_]

  def requestClName: String = rqCl.getName
}

case class ByDepHandlerImpl[RequestIn <: DepRequest]
(rqCl: Class[RequestIn])
  (val handle: RequestIn ⇒ Dep[_])
  extends ByDepHandlerApi(rqCl) with InternalDepHandler

case class AddDepHandlerImpl[RequestIn <: DepRequest, ReasonIn <: DepRequest]
(requestCl: Class[RequestIn], rqCl: Class[ReasonIn])
  (val add: ReasonIn ⇒ Map[RequestIn, _])
  extends AddDepHandlerApi(requestCl, rqCl) with InternalDepHandler

case class DepAskImpl[In<:Product,Out](name: String, depFactory: DepFactory)(val inClass: Class[In]) extends DepAsk[In,Out] {
  def ask: In ⇒ Dep[Out] = request ⇒ depFactory.uncheckedRequestDep[Out](request)

  def by(handler: In ⇒ Dep[Out]): ByDepHandlerApi[In] = ByDepHandlerImpl(inClass)(handler.asInstanceOf[In ⇒ Dep[_]])

  def add[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ Map[In, Out]): AddDepHandlerApi[In, ReasonIn] =
    AddDepHandlerImpl(inClass, reason match { case a: DepAskImpl[ReasonIn, _] ⇒ a.inClass })(handler.asInstanceOf[ReasonIn ⇒ Map[In, _]])
}

case class DepAskFactoryImpl(depFactory: DepFactory) extends DepAskFactory {
  def forClasses[In<:Product,Out](in: Class[In], out: Class[Out]): DepAsk[In,Out] =
    DepAskImpl[In,Out](in.getName, depFactory)(in)
}

//handler(in).resolve(ctx)
// ContextId: ContextIdRequest() →
