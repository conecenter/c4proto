
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.Single
import ee.cone.c4actor._
import ee.cone.c4proto._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean
}

class HttpGetHandler(worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "GET") return false
    val path = httpExchange.getRequestURI.getPath
    val local = worldProvider.createTx()
    val world = TxKey.of(local).world
    val publication = Single(By.srcId(classOf[HttpPublication]).of(world)(path))
    val headers = httpExchange.getResponseHeaders
    publication.headers.foreach(header⇒headers.add(header.key,header.value))
    val bytes = publication.body.toByteArray
    httpExchange.sendResponseHeaders(200, bytes.length)
    if(bytes.nonEmpty) httpExchange.getResponseBody.write(bytes)
    true
  }
}

class HttpPostHandler(qMessages: QMessages, worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    val req = HttpPost(UUID.randomUUID.toString, path, headers, body,  System.currentTimeMillis)
    Option(worldProvider.createTx())
      .map(LEvent.add(LEvent.update(req)))
      .foreach(qMessages.send)
    httpExchange.sendResponseHeaders(200, 0)
    true
  }
}

class ReqHandler(handlers: List[RHttpHandler]) extends HttpHandler {
  def handle(httpExchange: HttpExchange) = Trace{ try {
    handlers.find(_.handle(httpExchange))
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

class WorldProviderImpl(
  reducer: Reducer,
  worldFuture: CompletableFuture[()⇒World] = new CompletableFuture()
) extends WorldProvider with Observer {
  def createTx(): World = worldFuture.get.apply()
  def activate(getWorld: () ⇒ World): Seq[Observer] = {
    worldFuture.complete(()⇒reducer.createTx(getWorld())(Map()))
    Nil
  }
}

trait InternetForwarderApp extends ProtocolsApp with InitialObserversApp {
  def qReducer: Reducer
  lazy val worldProvider: WorldProvider with Observer = new WorldProviderImpl(qReducer)
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] = worldProvider :: super.initialObservers
}

trait HttpServerApp extends ToStartApp {
  def config: Config
  def worldProvider: WorldProvider
  def httpHandlers: List[RHttpHandler]
  private lazy val httpPort = config.get("C4HTTP_PORT").toInt
  lazy val httpServer: Executable =
    new RHttpServer(httpPort, new ReqHandler(new HttpGetHandler(worldProvider) :: httpHandlers))

  override def toStart: List[Executable] = httpServer :: super.toStart
}
