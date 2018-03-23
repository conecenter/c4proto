package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.{ContextId, DepCtx, DepRequest}
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4assemble.Types.Values

trait RequestHandler[A] {
  def canHandle: Class[A]

  def handle: A => (Dep[_], ContextId)
}

trait RequestHandlerRegistry {
  def handle: DepRequest ⇒ Option[(Dep[_], ContextId)]

  def buildContext: Values[DepResponse] ⇒ ContextId ⇒ DepCtx = responses ⇒ contextId ⇒ responses.map(curr ⇒ (curr.request.request, curr.value)).toMap + (ContextIdRequest() → Option(contextId))

  def buildContextWoSession: Values[DepResponse] ⇒ DepCtx = responses ⇒ responses.map(curr ⇒ (curr.request.request, curr.value)).toMap
}

case class RequestHandlerRegistryImpl(handlers: List[RequestHandler[_]]) extends RequestHandlerRegistry {
  private lazy val handlerMap: Map[Class[_], RequestHandler[_]] = handlers.map(handler ⇒ (handler.canHandle, handler)).toMap

  override def handle: DepRequest => Option[(Dep[_], ContextId)] = request ⇒ {
    val handler: Option[RequestHandler[DepRequest]] = handlerMap.get(request.getClass).map(_.asInstanceOf[RequestHandler[DepRequest]])
    handler.map(_.handle(request))
  }
}

trait RqHandlerRegistryImplApp extends RequestHandlerRegistryApp {
  lazy val handlerRegistryImpl = RequestHandlerRegistryImpl(handlers)

  def handlerRegistry: RequestHandlerRegistry = handlerRegistryImpl
}

trait RequestHandlerRegistryApp {
  def handlers: List[RequestHandler[_]] = Nil
}