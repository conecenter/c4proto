
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture

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

class HttpGetHandler(worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val path = httpExchange.getRequestURI.getPath
    val local = worldProvider.createTx()
    val world = TxKey.of(local).world
    Single(By.srcId(classOf[HttpPublication]).of(world)(path)).body.toByteArray
  }
}

class HttpPostHandler(qMessages: QMessages, worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    val req = HttpPost(UUID.randomUUID.toString, path, headers, body,  System.currentTimeMillis)
    Option(worldProvider.createTx())
      .map(LEvent.add(Seq(LEvent.update(req))))
      .foreach(qMessages.send)
    //println(s"ht:${tx.toSend.size}")

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
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  override def initialObservers: List[Observer] = worldProvider :: super.initialObservers
}

trait HttpServerApp extends ToStartApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  private lazy val httpPort = config.get("C4HTTP_PORT").toInt
  lazy val httpServer: Executable = {
    val handler = new ReqHandler(Map(
      "GET" → new HttpGetHandler(worldProvider),
      "POST" → new HttpPostHandler(qMessages,worldProvider)
    ))
    new RHttpServer(httpPort, handler)
  }
  override def toStart: List[Executable] = httpServer :: super.toStart
}
