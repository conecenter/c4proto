
package ee.cone.c4gate_server

import akka.util.ByteString
import ee.cone.c4actor._
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate.HttpProtocol.{S_HttpRequest, S_HttpResponse}
import okio.ByteString

import scala.concurrent.{ExecutionContext, Future}

// inner (TxTr-like) handler api
case class RHttpResponse(instantResponse: Option[S_HttpResponse], events: List[LEvent[Product]])
object RHttpTypes {
  type RHttpHandler = (S_HttpRequest,Context)=>RHttpResponse
  type RHttpHandlerCreate = RHttpHandler=>RHttpHandler
}

// outer handler api
case class FHttpRequest(method: String, path: String, rawQueryString: Option[String], headers: List[N_Header], body: ByteString)
trait FHttpHandler {
  def handle(request: FHttpRequest)(implicit executionContext: ExecutionContext): Future[S_HttpResponse]
}

trait RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse
}

class TxRes[R](val value: R, val next: RichContext=>Boolean)
trait WorldProvider {
  def tx[R](cond: RichContext=>Boolean)(
    f: Context=>(List[LEvent[Product]],R)
  )(implicit executionContext: ExecutionContext): Future[TxRes[R]]
  //def sync(local: Option[Context])(implicit executionContext: ExecutionContext): Future[Context]
}

trait WorldSource {
  def take[T](by: RichContext=>Option[T]): Future[T]
}

trait FromAlienStatusUpdater {
  def pong(logKey: String, value: String): Unit
}

trait AsyncEventLogReader {
  def read(logKey: String, pos: Long): Future[(Long, String)]
}
