
package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4proto._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.concurrent.TrieMap


object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}

class Handler(sender: Sender, staticRoot: String⇒Array[Byte]) extends HttpHandler {
  def handle(httpExchange: HttpExchange) = Trace{ try {
    val path = httpExchange.getRequestURI.getPath
    val bytes: Array[Byte] = httpExchange.getRequestMethod match {
      case "GET" ⇒
        staticRoot(path)
      case "POST" ⇒
        val headers = httpExchange.getRequestHeaders.asScala
          .flatMap{ case(k,l)⇒l.asScala.map(v⇒HttpProtocol.Header(k,v)) }.toList
        val buffer = new okio.Buffer
        val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
        sender.send(HttpProtocol.RequestValue(path, headers, body)).get()
        Array.empty[Byte]
    }
    httpExchange.sendResponseHeaders(200, bytes.length)
    if(bytes.length > 0) httpExchange.getResponseBody.write(bytes)
  } finally httpExchange.close() }
}

object Pool {
  def apply(): ExecutorService = {
    val pool: ExecutorService = Executors.newCachedThreadPool() //newWorkStealingPool
    OnShutdown(()⇒{
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    pool
  }
}

class Server(pool: ExecutorService, httpPort: Int, handler: HttpHandler) {
  def start(): Unit = {
    val server: HttpServer = HttpServer.create(new InetSocketAddress(httpPort),0)
    OnShutdown(()⇒server.stop(Int.MaxValue))
    server.setExecutor(pool)
    server.createContext("/", handler)
    server.start()
  }
}



class HttpGateway(httpPort: Int, bootstrapServers: String, postTopic: String, getTopic: String) {
  def state: ConsumerState = consumer.state
  private lazy val producer = Producer(bootstrapServers)
  private lazy val findAdapter = new FindAdapter(Seq(HttpProtocol))()
  private lazy val toSrcId = new Handling[String](findAdapter)
    .add(classOf[HttpProtocol.RequestValue])((r:HttpProtocol.RequestValue)⇒r.path)
  private lazy val sender = new Sender(producer, postTopic, findAdapter, toSrcId)
  private lazy val reduce = new Handling[Unit](findAdapter)
      .add(classOf[HttpProtocol.RequestValue])(
        (resp: HttpProtocol.RequestValue) ⇒
          staticRoot(resp.path) = resp.body.toByteArray
      )
  private lazy val receiver = new Receiver(findAdapter, reduce)
  private lazy val staticRoot = TrieMap[String,Array[Byte]]()
  private lazy val consumer =
    new ToStoredConsumer(bootstrapServers, getTopic, 0)(pool, receiver.receive)
  private lazy val pool = Pool()
  private lazy val handler = new Handler(sender, staticRoot)
  private lazy val server = new Server(pool, httpPort, handler)
  def start(): Unit = {
    val consumerFuture = pool.submit(consumer)
    server.start()
  }
}
