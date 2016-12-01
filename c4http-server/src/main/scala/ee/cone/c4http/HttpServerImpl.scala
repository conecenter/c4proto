package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4proto.{OnShutdown, Single}

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte]
}

class HttpGetHandler(provider: HttpContentProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] =
    Single(provider.get(httpExchange.getRequestURI.getPath).map(_.body.toByteArray))
}

class HttpPostHandler(httpPostObserver: HttpPostObserver) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒HttpProtocol.Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    httpPostObserver.received(HttpProtocol.RequestValue(path, headers, body))
    //sender.sendUpdate(path, )
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

class RHttpServer(port: Int, handler: HttpHandler, pool: ExecutorService) extends CanStart {
  def start(): Unit = {
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    OnShutdown(()⇒server.stop(Int.MaxValue))
    server.setExecutor(pool)
    server.createContext("/", handler)
    server.start()
  }
}

trait HttpServerApp extends ToStartApp {
  def httpPort: Int
  def pool: ExecutorService
  def httpPostObserver: HttpPostObserver
  def httpContentProvider: HttpContentProvider
  lazy val httpServer: CanStart = {
    val handler = new ReqHandler(Map(
      "GET" → new HttpGetHandler(httpContentProvider),
      "POST" → new HttpPostHandler(httpPostObserver)
    ))
    new RHttpServer(httpPort, handler, pool)
  }
  override def toStart: List[CanStart] = httpServer :: super.toStart
}

