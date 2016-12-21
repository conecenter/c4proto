
package ee.cone.c4gate

import java.net.InetSocketAddress


import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4actor.Types.World
import ee.cone.c4actor._
import ee.cone.c4proto._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte]
}

class HttpGetHandler(getWorld: ()⇒World) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val path = httpExchange.getRequestURI.getPath
    val publishedByPath = By.srcId(classOf[HttpRequestValue])
    Single(publishedByPath.of(getWorld())(path)).body.toByteArray
  }
}

class HttpPostHandler(qMessages: QMessages) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    val req = HttpRequestValue(path, headers, body)
    // qMessages.send(LEvent.update(path, req))
    ???
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

class RHttpServer(port: Int, handler: HttpHandler) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    ctx.onShutdown(()⇒server.stop(Int.MaxValue))
    server.setExecutor(ctx.executors)
    server.createContext("/", handler)
    server.start()
  }
}

trait InternetForwarderApp extends ProtocolsApp {
  def worldProvider: WorldProvider
  //()⇒worldProvider.world
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
}

trait HttpServerApp extends ToStartApp {
  def httpPort: Int
  def qMessages: QMessages
  def worldProvider: WorldProvider
  lazy val httpServer: Executable = {
    val handler = new ReqHandler(Map(
      "GET" → new HttpGetHandler(()⇒worldProvider.world),
      "POST" → new HttpPostHandler(qMessages)
    ))
    new RHttpServer(httpPort, handler)
  }
  override def toStart: List[Executable] = httpServer :: super.toStart
}
