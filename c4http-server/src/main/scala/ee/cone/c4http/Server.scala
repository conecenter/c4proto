
package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4proto.Types.{Index, World}
import ee.cone.c4proto._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.internals.Sender

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.concurrent.TrieMap


object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}

class Handler(sender: QRecords, staticRoot: String⇒Array[Byte]) extends HttpHandler {
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
        sender.sendUpdate(path, HttpProtocol.RequestValue(path, headers, body))
        Array.empty[Byte]
    }
    httpExchange.sendResponseHeaders(200, bytes.length)
    if(bytes.length > 0) httpExchange.getResponseBody.write(bytes)
  } finally httpExchange.close() }
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

case object StaticRootKey extends WorldKey[Index[String,okio.ByteString]](Map.empty)

object HttpGateway {
  def main(args: Array[String]): Unit = try {
    val bootstrapServers = Option(System.getenv("C4BOOTSTRAP_SERVERS")).get
    val httpPort = Option(System.getenv("C4HTTP_PORT")).get.toInt
    val postTopic = Option(System.getenv("C4HTTP_POST_TOPIC")).getOrElse("http-posts")
    val getTopic = Option(System.getenv("C4HTTP_GET_TOPIC")).getOrElse("http-gets")
    val ssePort = Option(System.getenv("C4SSE_PORT")).get.toInt
    ////
    val sseService = new TcpService(ssePort)
    //val staticRoot = TrieMap[String,Array[Byte]]()
    val handlerLists = CoHandlerLists(
      CoHandler(ProtocolKey)(QProtocol) ::
      CoHandler(ProtocolKey)(HttpProtocol) ::
      CoHandler(ReceiverKey)(new Receiver(classOf[HttpProtocol.RequestValue],
        (world: World, resp: HttpProtocol.RequestValue) ⇒
          Map(StaticRootKey → Map(resp.path → resp.body :: Nil)) //.toByteArray
      )) ::
      Nil
    )
    val producer = Producer(bootstrapServers)
    val qRecords = QRecords(handlerLists)(
      (k:Array[Byte],v:Array[Byte]) ⇒ producer.send(new ProducerRecord(postTopic, k, v)).get()
    )
    val pool = Pool()
    var world: World = Map.empty
    val reducer = Reducer(handlerLists)
    val consumer =
      new ToStoredConsumer(bootstrapServers, getTopic, 0)(pool, { (messages: Iterable[QRecord]) ⇒
        messages.foreach{ message ⇒
          val diff = qRecords.receive(world, message)
          world = reducer.reduce(world, diff)
        }
      })
    val handler = new Handler(qRecords, path ⇒ world(StaticRootKey)(path).head.toByteArray)
    val server = new Server(pool, httpPort, handler)
    ////
    consumer.start()
    server.start()

    while(consumer.state != Finished) {
      //println(consumer.state)
      Thread.sleep(1000)
    }
  } finally System.exit(0)
}
