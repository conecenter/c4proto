package ee.cone.c4actor.dependancy

import ee.cone.c4actor.CtxType.{ContextId, Ctx, Request}
import ee.cone.c4actor.dependancy.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4assemble.Types.Values

trait RequestHandler[A] {
  def canHandle: Class[A]

  def handle: A => (Dep[_], ContextId)
}

trait RequestHandlerRegistry {
  def handle: Request ⇒ Option[(Dep[_], ContextId)]

  def buildContext: Values[Response] => ContextId ⇒ Ctx = responses ⇒ contextId ⇒ responses.map(curr ⇒ (curr.request.request, curr.value)).toMap + (ContextIdRequest() → Option(contextId))
}

case class RequestHandlerRegistryImpl(handlers: List[RequestHandler[_]]) extends RequestHandlerRegistry {
  private lazy val handlerMap: Map[Class[_], RequestHandler[_]] = handlers.map(handler ⇒ (handler.canHandle, handler)).toMap

  override def handle: Request => Option[(Dep[_], ContextId)] = request ⇒ {
    val handler: Option[RequestHandler[Request]] = handlerMap.get(request.getClass).map(_.asInstanceOf[RequestHandler[Request]])
    handler.map(_.handle(request))
  }
}

trait RqHandlerRegistryImplApp extends RequestHandlerRegistryApp {
  def handlerRegistry: RequestHandlerRegistry = handlerRegistryImpl

  lazy val handlerRegistryImpl = RequestHandlerRegistryImpl(handlers)
}

trait RequestHandlerRegistryApp {
  def handlers: List[RequestHandler[_]] = Nil
}