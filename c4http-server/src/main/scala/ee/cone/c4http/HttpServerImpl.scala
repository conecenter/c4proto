package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4http.HttpProtocol._
import ee.cone.c4proto._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}

trait RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte]
}

class HttpGetHandler(worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val path = httpExchange.getRequestURI.getPath
    val worldKey = By.srcId(classOf[RequestValue])
    Single(worldKey.of(worldProvider.world)(path)).body.toByteArray
  }
}

class HttpPostHandler(qMessages: QMessages, streamKey: StreamKey, rawQSender: RawQSender) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    val req = RequestValue(path, headers, body)
    rawQSender.send(streamKey, qMessages.update("", req))
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
  def start(pool: ExecutorService): Unit = {
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    OnShutdown(()⇒server.stop(Int.MaxValue))
    server.setExecutor(pool)
    server.createContext("/", handler)
    server.start()
  }
}

trait HttpServerApp extends ToStartApp with ProtocolsApp {
  def httpPort: Int
  def qMessages: QMessages
  def httpPostStreamKey: StreamKey
  def rawQSender: RawQSender
  def worldProvider: WorldProvider
  lazy val httpServer: CanStart = {
    val handler = new ReqHandler(Map(
      "GET" → new HttpGetHandler(worldProvider),
      "POST" → new HttpPostHandler(qMessages, httpPostStreamKey, rawQSender)
    ))
    new RHttpServer(httpPort, handler)
  }
  override def toStart: List[CanStart] = httpServer :: super.toStart
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
}
