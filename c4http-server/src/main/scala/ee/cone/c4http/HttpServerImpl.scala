package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4http.InternetProtocol._
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
    val publishedByPath = By.srcId(classOf[HttpRequestValue])
    Single(publishedByPath.of(worldProvider.world)(path)).body.toByteArray
  }
}

class ForwarderConfigImpl(worldProvider: WorldProvider) extends ForwarderConfig {
  def targets(path: String): List[ActorName] =
    By.srcId(classOf[ForwardingConf]).of(worldProvider.world).collect{
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
    targets.foreach(actorName ⇒ qMessages.send(Send(actorName, req)))
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

object HttpPublishMapper extends MessageMapper(classOf[HttpRequestValue]) {
  def mapMessage(world: World, message: HttpRequestValue): Seq[MessageMapResult] =
    Seq(Update(message.path, message))
}


object ForwardingConfMapper extends MessageMapper(classOf[ForwardingConf]) {
  def mapMessage(world: World, message: ForwardingConf): Seq[MessageMapResult] =
    if(message.rules.isEmpty) Seq(Delete(message.actorName, classOf[ForwardingConf]))
    else Seq(Update(message.actorName, message))
}

trait InternetForwarderApp extends ProtocolsApp with MessageMappersApp {
  def worldProvider: WorldProvider
  lazy val internetForwarderConfig: ForwarderConfig = new ForwarderConfigImpl(worldProvider)
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  override def messageMappers: List[MessageMapper[_]] =
    ForwardingConfMapper :: super.messageMappers
}

trait HttpServerApp extends ToStartApp with MessageMappersApp {
  def httpPort: Int
  def qMessages: QMessages
  def worldProvider: WorldProvider
  def internetForwarderConfig: ForwarderConfig
  lazy val httpServer: CanStart = {
    val handler = new ReqHandler(Map(
      "GET" → new HttpGetHandler(worldProvider),
      "POST" → new HttpPostHandler(internetForwarderConfig,qMessages)
    ))
    new RHttpServer(httpPort, handler)
  }
  override def toStart: List[CanStart] = httpServer :: super.toStart
  override def messageMappers: List[MessageMapper[_]] =
    HttpPublishMapper :: super.messageMappers
}
