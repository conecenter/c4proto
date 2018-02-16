package ee.cone.c4actor.dependancy

trait RequestHandler[A] {
  def canHandle: Class[A]

  def handle: A => Dep[_]
}

trait RequestHandlerRegistry {
  def canHandle: Class[_] ⇒ Boolean

  def getHandler: Class[_] ⇒ RequestHandler[_]

  def handle: Request ⇒ Dep[_]
}

case class RequestHandlerRegistryImpl(handlers: List[RequestHandler[_]]) extends RequestHandlerRegistry {
  private lazy val handlerMap: Map[Class[_], RequestHandler[_]] = handlers.map(handler ⇒ (handler.canHandle, handler)).toMap

  override def canHandle: Class[_] => Boolean = handlerMap.contains

  override def getHandler: Class[_] => RequestHandler[_] = className ⇒ if (handlerMap.contains(className)) handlerMap(className) else throw new Exception(s"$className: Given class name is not in Registry")

  override def handle: Request => Dep[_] = request ⇒ {
    if (canHandle(request.getClass)) {
      val handler: RequestHandler[_] = getHandler(request.getClass)
      handler.asInstanceOf[RequestHandler[Request]].handle(request)
    }
    else
      throw new Exception(s"${request.getClass}: Given class name is not in Registry")
  }
}

trait RqHandlerRegistryImplApp extends RequestHandlerRegistryApp {
  def handlerRegistry: RequestHandlerRegistry = handlerRegistryImpl

  lazy val handlerRegistryImpl = RequestHandlerRegistryImpl(handlers)
}

trait RequestHandlerRegistryApp {
  def handlers: List[RequestHandler[_]] = Nil
}