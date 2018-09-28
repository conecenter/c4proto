package ee.cone.c4actor.dep_impl

import scala.collection.immutable.{Map, Seq}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.DepTypes._
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.ToByteString

case class DepResponseImpl(innerRequest: DepInnerRequest, valueHashed: PreHashed[Option[_]]) extends DepResponse {
  def value: Option[_] = valueHashed.value
}
case class DepInnerResolvable(result: DepResponse, subRequests: Seq[(SrcId,DepOuterRequest)])

@assemble class DepAssemble(reg: DepRequestHandlerRegistry) extends Assemble {

  def resolvableToSubRequests(
    innerId: SrcId,
    @was resolvable: Each[DepInnerResolvable]
  ): Values[(GroupId, DepOuterRequest)] = resolvable.subRequests

  def toSingleInnerRequest(
    innerId: SrcId,
    @by[GroupId] outerRequests: Values[DepOuterRequest]
  ): Values[(SrcId,DepInnerRequest)] =
    Seq(WithPK(Single(outerRequests.map(_.innerRequest).distinct)))

  def resolvableToResponse(
    innerId: SrcId,
    @was resolvable: Each[DepInnerResolvable]
  ): Values[(SrcId, DepResponse)] = List(WithPK(resolvable.result))

  def responseToParent(
    innerId: SrcId,
    response: Each[DepResponse],
    @by[GroupId] outer: Each[DepOuterRequest]
  ): Values[(GroupId, DepResponse)] = List(outer.parentSrcId → response)

  def addResponsesToInnerRequest(
    innerId: SrcId,
    innerRequest: Each[DepInnerRequest]
  ): Values[(GroupId, DepResponse)] = reg.add(innerRequest)

  def outerToParentId(
    outerId: SrcId,
    @by[GroupId] outer: Each[DepOuterRequest]
  ): Values[(ParentId, DepOuterRequest)] = List(outer.parentSrcId → outer)

  def responsesToChild(
    groupId: SrcId,
    inner: Each[DepInnerRequest],
    @by[ParentId] outer: Each[DepOuterRequest],
    @by[GroupId] response: Each[DepResponse]
  ): Values[(AddId, DepResponse)] = reg.filter(inner, outer, response)

  def handle(
    innerId: SrcId,
    req: Each[DepInnerRequest],
    @by[GroupId] responses: Values[DepResponse],
    @by[AddId] addResponses: Values[DepResponse]
  ): Values[(SrcId, DepInnerResolvable)] = for {
    handle ← reg.handle(req).toList
  } yield WithPK(handle(addResponses ++ responses))

  def unresolvedRequestProducer(
    requestId: SrcId,
    rq: Each[DepInnerRequest],
    @by[GroupId] outerRequests: Values[DepOuterRequest],
    responses: Values[DepResponse]
  ): Values[(SrcId, DepUnresolvedRequest)] =
    if (responses.forall(_.value.isEmpty))
      List(WithPK(DepUnresolvedRequest(rq.srcId, rq.request, responses.size, outerRequests.map(_.srcId).toList)))
    else Nil
}

case class DepRequestHandlerRegistry(
  depOuterRequestFactory: DepOuterRequestFactory,
  depResponseFactory: DepResponseFactory,
  handlerSeq: Seq[DepHandler],
  filtersSeq: Seq[DepResponseForwardFilter]
)(
  handlers: Map[String,(DepRequest,DepCtx)⇒Resolvable[_]] =
    handlerSeq.collect{ case h: InternalDepHandler ⇒ h }.groupBy(_.className)
      .transform { (k, handlers) ⇒
        val by = Single(handlers.collect { case h: ByDepHandler ⇒ h.handler })
        (req: DepRequest, ctx: DepCtx) ⇒
        by(req).resolve(ctx)
      },
  addHandlers: Map[String, DepInnerRequest ⇒ Seq[(DepRequest,_)]] =
    handlerSeq.collect{ case h: InternalDepHandler ⇒ h }.groupBy(_.className)
      .transform { (k, handlers) ⇒
        val adds: Seq[DepRequest => DepCtx] = handlers.collect { case h: AddDepHandler ⇒ h.handler }
        (req: DepInnerRequest) ⇒ adds.flatMap(_(req.request))
      },
  filters: Map[Option[String], Map[String, DepResponse ⇒ Option[DepResponse]]] = {
    filtersSeq
      .groupBy(_.parentCl.map(_.getClass.getName))
      .map { case (k, v) ⇒ k → v.groupBy(_.childCl.getName).transform((_, filters) ⇒ Single(filters).filter) }
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
      DepInnerResolvable(response, resolvable.requests.distinct.map(depOuterRequestFactory.tupled(req.srcId)))
    }

  def add(req: DepInnerRequest): Values[(String, DepResponse)] =
    addHandlers.get(req.request.getClass.getName)
      .map { (add: DepInnerRequest ⇒ Seq[(DepRequest, _)]) ⇒
        add(req)
          .map { case (rq, rsp) ⇒ (req.srcId, depResponseFactory.wrap(depOuterRequestFactory.innerRequest(rq), Option(rsp))) }
      }.getOrElse(Nil)

  def filter(parent: DepInnerRequest, child: DepOuterRequest, response: DepResponse): Values[(String, DepResponse)] =
    (filters.get(Some(parent.request.getClass.getName)) match {
      case None ⇒
        filters.getOrElse(None, Map.empty)
      case Some(childMap) ⇒
        childMap
    }).getOrElse(child.innerRequest.request.getClass.getName, (_:DepResponse) ⇒ None)(response).map(resp ⇒ child.innerRequest.srcId → resp).toList


}

case class DepResponseFactoryImpl()(preHashing: PreHashing) extends DepResponseFactory {
  def wrap(req: DepInnerRequest, value: Option[_]): DepResponse =
    DepResponseImpl(req,preHashing.wrap(value))
}

case class DepOuterRequestFactoryImpl(idGenUtil: IdGenUtil)(qAdapterRegistry: QAdapterRegistry) extends DepOuterRequestFactory {
  def tupled(parentId: SrcId)(rq: DepRequest): (SrcId,DepOuterRequest) = {
    val inner = innerRequest(rq)
    val outerId = idGenUtil.srcIdFromSrcIds(parentId, inner.srcId)
    inner.srcId → DepOuterRequest(outerId, inner, parentId)
  }

  def innerRequest(rq: DepRequest): DepInnerRequest = {
    val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(rq))
    val innerId = idGenUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    DepInnerRequest(innerId, rq)
  }
}

abstract class InternalDepHandler(val className: String) extends DepHandler
case class ByDepHandler(name: String)(val handler: DepRequest ⇒ Dep[_]) extends InternalDepHandler(name)
case class AddDepHandler(name: String)(val handler: DepRequest ⇒ DepCtx) extends InternalDepHandler(name)
case class DepAskImpl[In<:Product,Out](name: String, depFactory: DepFactory)(val inClass: Class[In]) extends DepAsk[In,Out] {
  def ask: In ⇒ Dep[Out] = request ⇒ depFactory.uncheckedRequestDep[Out](request)
  def by(handler: In ⇒ Dep[Out]): DepHandler = ByDepHandler(name)(handler.asInstanceOf[DepRequest ⇒ Dep[_]])
  def byParent[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ Map[In, Out]): DepHandler =
    AddDepHandler(reason match { case DepAskImpl(nm,_) ⇒ nm })(handler.asInstanceOf[DepRequest ⇒ DepCtx])
}
case class DepAskFactoryImpl(depFactory: DepFactory) extends DepAskFactory {
  def forClasses[In<:Product,Out](in: Class[In], out: Class[Out]): DepAsk[In,Out] =
    DepAskImpl[In,Out](in.getName, depFactory)(in)
}

//handler(in).resolve(ctx)
// ContextId: ContextIdRequest() →
