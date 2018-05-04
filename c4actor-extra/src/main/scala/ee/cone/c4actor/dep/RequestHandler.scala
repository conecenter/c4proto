package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.DepTypeContainer.{ContextId, DepCtx, DepRequest}
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4assemble.Types.Values

trait RequestHandler[A] {
  def canHandle: Class[A]

  def handle: A => (Dep[_], ContextId)
}

trait RequestHandlerRegistry {
  def handle: DepRequest ⇒ Option[(Dep[_], ContextId)]

  def buildContext: Values[DepOuterResponse] ⇒ ContextId ⇒ DepCtx = responses ⇒ contextId ⇒ responses.map(curr ⇒ (curr.request.innerRequest.request, curr.value)).toMap + (ContextIdRequest() → Option(contextId))

  def buildContextWoSession: Values[DepOuterResponse] ⇒ DepCtx = responses ⇒ responses.map(curr ⇒ (curr.request.innerRequest.request, curr.value)).toMap

  def handleAndBuildContext(request: DepInnerRequest, responses: Values[DepOuterResponse]): Option[Resolvable[_]] = {
    handle(request.request) match {
      case Some((dep, contextId)) ⇒
        val ctx = buildContext(responses)(contextId)
        Some(dep.asInstanceOf[InnerDep[_]].resolve(ctx))
      case None ⇒ None
    }
  }
}

case class RequestHandlerRegistryImpl(handlers: List[RequestHandler[_]]) extends RequestHandlerRegistry {
  private lazy val handlerMap: Map[String, RequestHandler[_]] = handlers.map(handler ⇒ (handler.canHandle.getName, handler)).toMap

  override def handle: DepRequest => Option[(Dep[_], ContextId)] = request ⇒ {
    val handler: Option[RequestHandler[DepRequest]] = handlerMap.get(request.getClass.getName).map(_.asInstanceOf[RequestHandler[DepRequest]])
    handler.map(_.handle(request))
  }
}

trait RequestHandlerRegistryImplApp extends RequestHandlerRegistryApp with RequestHandlersApp{
  lazy val handlerRegistryImpl = RequestHandlerRegistryImpl(handlers)

  def handlerRegistry: RequestHandlerRegistry = handlerRegistryImpl
}

trait RequestHandlerRegistryApp {
  def handlerRegistry: RequestHandlerRegistry
}

trait RequestHandlersApp {
  def handlers: List[RequestHandler[_]] = Nil
}