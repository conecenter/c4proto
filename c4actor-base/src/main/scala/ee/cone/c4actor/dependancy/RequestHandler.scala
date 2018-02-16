package ee.cone.c4actor.dependancy

trait RequestHandler[A] {
  def canHandle: Class[A]

  def handle: A => Dep[_]
}

trait RequestHandlerRegistry {
  def canHandle: Class[_] ⇒ Boolean

  def getHandler: Class[_] ⇒ RequestHandler[_]

  def handle: Request ⇒ Option[Dep[_]]
}

case class RequestHandlerRegistryImpl(handlers: List[RequestHandler[_]]) extends RequestHandlerRegistry {
  private lazy val handlerMap: Map[Class[_], RequestHandler[_]] = handlers.map(handler ⇒ (handler.canHandle, handler)).toMap

  override def canHandle: Class[_] => Boolean = handlerMap.contains

  override def getHandler: Class[_] => RequestHandler[_] = className ⇒ if (handlerMap.contains(className)) handlerMap(className) else throw new Exception(s"$className: Given class name is not in Registry")

  override def handle: Request => Option[Dep[_]] = request ⇒ { //TODO use map once
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