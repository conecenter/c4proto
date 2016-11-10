
package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4proto.{FindAdapter, Protocol, protocol}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.{iterableAsScalaIterableConverter, mapAsJavaMapConverter, seqAsJavaListConverter}
import org.apache.kafka.common.serialization.ByteArraySerializer


object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}

@protocol object HttpProtocol extends Protocol {
  case class POSTRequestKey(path: String)
  case class POSTRequestValue(headers: List[Header], body: okio.ByteString)
  case class Header(key: String, value: String)
}

class Handler(sender: Sender) extends HttpHandler {
  def handle(httpExchange: HttpExchange) = Trace{ try {
    val path = httpExchange.getRequestURI.getPath

    val bytes: Array[Byte] = httpExchange.getRequestMethod match {
      case "GET" ⇒ ???
      case "POST" ⇒
        val headers = httpExchange.getRequestHeaders.asScala
          .flatMap((k,l)⇒l.asScala.map(v⇒HttpProtocol.Header(k,v)))
        val buffer = new okio.Buffer
        val body = buffer.readFrom(httpExchange.getRequestBody).readByteString()
        val messageKey = HttpProtocol.POSTRequestKey(path)
        val messageValue = HttpProtocol.POSTRequestValue(headers, body)
        sender.blockingSend(messageKey, messageValue)
        Array.empty[Byte]
    }
    httpExchange.sendResponseHeaders(200, bytes.length)
    if(bytes.length > 0) httpExchange.getResponseBody.write(bytes)
  } finally httpExchange.close() }
}

object Pool {
  def apply(threadCount: Int): ExecutorService = {
    val pool: ExecutorService = Executors.newScheduledThreadPool(threadCount)
    OnShutdown(()⇒{
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    pool
  }
}

object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
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

object Producer {
  def apply(bootstrapServers: String): KafkaProducer[Array[Byte], Array[Byte]] = {
    val props = Map[String, Object](
      "bootstrap.servers" → bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432"
    )
    val serializer = new ByteArraySerializer
    val producer = new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava,
      serializer,
      serializer
    )
    OnShutdown(() ⇒ producer.close())
    producer
  }
}
  //producer.send(new ProducerRecord(Config.topic, key, value))


/*
  val buffer = new okio.Buffer
  val writer = new com.squareup.wire.ProtoWriter(buffer)

  val adapterToId = ProtoAdapters.list.map(adapter ⇒ adapter→adapter.id).toMap

  //5000000 ~ 1/2 min
  (1 to 500000).foreach { i ⇒
    val obj = MyObj(Some(java.time.Instant.now()), List(
      MyInnerObj(Some("test"), Some(BigDecimal(i))),
      MyInnerObj(Some("test"), Some(BigDecimal(i+1)))
    ))
    val typeId: Int = adapterToId(MyObjProtoAdapter)
    KeyProtoAdapter.encode(writer, Key(Some(typeId)))
    //com.squareup.wire.ProtoAdapter.SINT32.encode(writer,typeId)
    val key = buffer.readByteArray()
    //println(key.toList)
    MyObjProtoAdapter.encode(writer, obj)
    val value = buffer.readByteArray()
    producer.send(new ProducerRecord(Config.topic, key, value))
    if(i % 100000 == 0) println(i)
  }*/

class Sender(producer: KafkaProducer[Array[Byte], Array[Byte]], topic: String, findAdapter: FindAdapter) {
  def blockingSend(key: HttpProtocol.POSTRequestKey, value: HttpProtocol.POSTRequestValue): Unit = {
    val rawKey: Array[Byte] = findAdapter(key).encode(key)
    val rawValue: Array[Byte] = findAdapter(value).encode(value)
    producer.send(new ProducerRecord(topic, rawKey, rawValue)).get()
  }
}


object ServerApp {
  def main(args: Array[String]): Unit = {
    val threadCount: Int = ???
    val httpPort: Int = ???
    val bootstrapServers: String = ??? //"localhost:9092"
    val topic: String = ???
    val producer = Producer(bootstrapServers)
    val findAdapter = new FindAdapter(Seq(HttpProtocol))
    val sender = new Sender(producer, topic, findAdapter)
    val handler = new Handler(sender)
    new Server(Pool(threadCount), httpPort, handler).start()
  }
}
