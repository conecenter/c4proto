package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4http.HttpProtocol._
import ee.cone.c4proto.Types.World
import ee.cone.c4proto._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte]
}

class HttpGetHandler(worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val path = httpExchange.getRequestURI.getPath
    val publishedByPath = By.srcId(classOf[RequestValue])
    Single(publishedByPath.of(worldProvider.world)(path)).body.toByteArray
  }
}

class HttpPostHandler(worldProvider: WorldProvider, qMessages: QMessages) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    val req = RequestValue(path, headers, body)
    val confByActor = By.srcId(classOf[RequestConf])
    confByActor.of(worldProvider.world).foreach{
      case (actorName, confList) ⇒
        if(confList.map(_.path).exists(p ⇒ p.nonEmpty && path.startsWith(p)))
          qMessages.send(Send(ActorName(actorName), req))
    }
    Array.empty[Byte]
  }
}

class ReqHandler(
  handlerByMethod: Map[String,RHttpHandler]
) extends HttpHandler {
  def handle(httpExchange: HttpExchange) = Trace{ try {
    val bytes: Array[Byte] = handlerByMethod(httpExchange.getRequestMethod).handle(httpExchange)
    httpExchange.sendResponseHeaders(200, bytes.length)
    if(bytes.length > 0) httpExchange.getResponseBody.write(bytes)
  } finally httpExchange.close() }
}

class RHttpServer(port: Int, handler: HttpHandler) extends CanStart {
  def early: Option[ShouldStartEarly] = None
  def start(ctx: ExecutionContext): Unit = {
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    ctx.onShutdown(()⇒server.stop(Int.MaxValue))
    server.setExecutor(ctx.executors)
    server.createContext("/", handler)
    server.start()
  }
}

class HttpPublishMapper(val actorName: ActorName) extends MessageMapper(classOf[RequestValue]) {
  def mapMessage(world: World, message: RequestValue): Seq[MessageMapResult] =
    Seq(Update(message.path, message))
}


class HttpConfMapper(val actorName: ActorName) extends MessageMapper(classOf[RequestConf]) {
  def mapMessage(world: World, message: RequestConf): Seq[MessageMapResult] =
    Seq(Update(message.actorName, message))
}

trait HttpServerApp extends ToStartApp with ProtocolsApp {
  def httpPort: Int
  def qMessages: QMessages
  def worldProvider: WorldProvider
  lazy val httpServer: CanStart = {
    val handler = new ReqHandler(Map(
      "GET" → new HttpGetHandler(worldProvider),
      "POST" → new HttpPostHandler(worldProvider, qMessages)
    ))
    new RHttpServer(httpPort, handler)
  }
  override def toStart: List[CanStart] = httpServer :: super.toStart
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
}
