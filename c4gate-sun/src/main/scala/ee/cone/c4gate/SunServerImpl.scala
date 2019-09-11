package ee.cone.c4gate

import java.lang.Math.toIntExact
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4actor.{Executable, Execution, FinallyClose, Trace}
import ee.cone.c4gate.HttpProtocol.N_Header

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, SECONDS}

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

/*
* this 'll be fallback impl:
* sun HttpServer is not so effective due to blocking
*  */

class SunReqHandler(handler: RHttpHandler, executionContext: ExecutionContext) extends HttpHandler {
  def handle(httpExchange: HttpExchange) =
    Trace{ FinallyClose[HttpExchange,Unit](_.close())(httpExchange) { ex ⇒
      val method = httpExchange.getRequestMethod
      val path = httpExchange.getRequestURI.getPath
      val reqHeaders: List[N_Header] = httpExchange.getRequestHeaders.asScala
        .flatMap{ case(k,l)⇒l.asScala.map(v⇒N_Header(k,v)) }.toList
      val buffer = (new okio.Buffer).readFrom(httpExchange.getRequestBody)
      val body = buffer.readByteString()
      val request = RHttpRequest(method, path, reqHeaders, body)
      val response = Await.result(handler.handle(request)(executionContext),Duration(600,SECONDS))
      val headers = httpExchange.getResponseHeaders
      response.headers.foreach(header⇒headers.add(header.key,header.value))
      val bytes = response.body.toByteArray
      httpExchange.sendResponseHeaders(toIntExact(response.status), bytes.length)
      if(bytes.nonEmpty) httpExchange.getResponseBody.write(bytes)
    } }
}

class SunHttpServer(port: Int, handler: RHttpHandler, execution: Execution) extends Executable {
  def run(): Unit = concurrent.blocking{
    val pool = execution.newThreadPool("http-") //newWorkStealingPool
    execution.onShutdown("Pool",()⇒{
      val tasks = pool.shutdownNow()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    val executionContext: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    execution.onShutdown("HttpServer",()⇒server.stop(Int.MaxValue))
    server.setExecutor(pool)
    server.createContext("/", new SunReqHandler(handler,executionContext))
    server.start()
  }
}
