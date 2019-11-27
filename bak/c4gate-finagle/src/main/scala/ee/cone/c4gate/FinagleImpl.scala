
package ee.cone.c4gate

import com.twitter.finagle.{Http, Service}
import com.twitter.finagle.http
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Return, Throw, Try => TTry}
import ee.cone.c4actor.{Executable, Execution}
import ee.cone.c4gate.HttpProtocolBase.{N_Header, S_HttpResponse}
import ee.cone.c4proto.ToByteString

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try => STry}

class FinagleService(handler: FHttpHandler)(implicit ec: ExecutionContext)
  extends Service[http.Request, http.Response]
{
  def apply(req: http.Request): Future[http.Response] = {
    val promise = Promise[http.Response]()
    val future: concurrent.Future[S_HttpResponse] = handler.handle(encode(req))
    future.onComplete(res => promise.update(decodeTry(res).map(decode)))
    promise
  }
  def encode(req: http.Request): FHttpRequest = {
    val method = req.method.name
    val path = req.path
    val headerMap = req.headerMap
    val headers = headerMap.keys.toList.sorted
      .flatMap(k=>headerMap.getAll(k).map(v=>N_Header(k,v)))
    val body = ToByteString(Buf.ByteArray.Owned.extract(req.content))
    FHttpRequest(method,path,headers,body)
  }
  def decode(response: S_HttpResponse): http.Response = {
    val finResponse = http.Response(http.Status(Math.toIntExact(response.status)))
    response.headers.foreach(h=>finResponse.headerMap.add(h.key,h.value))
    finResponse.write(Buf.ByteArray.Owned(response.body.toByteArray))
    finResponse
  }
  def decodeTry[T](res: STry[T]): TTry[T] = res match {
    case Success(v) => Return(v)
    case Failure(e) => Throw(e)
  }
}

class FinagleHttpServer(port: Int, handler: FHttpHandler, execution: Execution) extends Executable {
  def run(): Unit = Http.serve(s":$port", new FinagleService(handler)(ExecutionContext.fromExecutor(execution.newExecutorService("finagle-",None))))
}
