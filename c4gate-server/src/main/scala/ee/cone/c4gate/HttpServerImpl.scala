
package ee.cone.c4gate

import java.net.InetSocketAddress
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

class HttpGetHandler(
  worldFuture: CompletableFuture[()⇒World] = new CompletableFuture()
) extends RHttpHandler with WorldObserver {
  def activate(getWorld: () ⇒ World): Seq[WorldObserver] = {
    worldFuture.complete(getWorld)
    Nil
  }
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val path = httpExchange.getRequestURI.getPath
    val publishedByPath = By.srcId(classOf[HttpRequestValue])
    val world = worldFuture.get.apply()
    Single(publishedByPath.of(world)(path)).body.toByteArray
  }
}

class ForwarderConfigImpl(getWorld: ()⇒World) extends ForwarderConfig {
  def targets(path: String): List[ActorName] =
    By.srcId(classOf[ForwardingConf]).of(getWorld()).collect{
      case (actorName, confList)
        if confList.flatMap(_.rules).exists(r ⇒ path.startsWith(r.path)) ⇒
        ActorName(actorName)
    }.toList.sortBy(_.value)
}

class HttpPostHandler(forwarder: ForwarderConfig, qMessages: QMessages) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Array[Byte] = {
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val buffer = new okio.Buffer
    val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
    val path = httpExchange.getRequestURI.getPath
    val req = HttpRequestValue(path, headers, body)
    val targets = forwarder.targets(path)
    if(targets.isEmpty) throw new Exception("no handler")
    targets.foreach(actorName ⇒ qMessages.send(LEvent.update(actorName, path, req)))
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

object HttpPublishMapper extends MessageMapper(classOf[HttpRequestValue]) {
  def mapMessage(res: MessageMapping, message: LEvent[HttpRequestValue]): MessageMapping =
    res.add(message)
}


object ForwardingConfMapper extends MessageMapper(classOf[ForwardingConf]) {
  def mapMessage(res: MessageMapping, message: LEvent[ForwardingConf]): MessageMapping =
    res.add(message)
}

trait InternetForwarderApp extends ProtocolsApp with MessageMappersApp {
  def worldProvider: WorldProvider
  lazy val internetForwarderConfig: ForwarderConfig =
    new ForwarderConfigImpl(()⇒worldProvider.world)
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  override def messageMappers: List[MessageMapper[_]] =
    ForwardingConfMapper :: super.messageMappers
}

trait HttpServerApp extends ToStartApp with MessageMappersApp with WorldObserverApp {

  def httpPort: Int
  def qMessages: QMessages
  def internetForwarderConfig: ForwarderConfig
  lazy val httpGetHandler = new HttpGetHandler
  lazy val httpServer: Executable = {
    val handler = new ReqHandler(Map(
      "GET" → httpGetHandler,
      "POST" → new HttpPostHandler(internetForwarderConfig,qMessages)
    ))
    new RHttpServer(httpPort, handler)
  }
  override def toStart: List[Executable] = httpServer :: super.toStart
  override def messageMappers: List[MessageMapper[_]] =
    HttpPublishMapper :: super.messageMappers
  override def initialWorldObservers: List[WorldObserver] =
    httpGetHandler :: super.initialWorldObservers
}
