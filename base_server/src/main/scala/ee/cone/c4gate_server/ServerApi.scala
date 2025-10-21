
package ee.cone.c4gate_server

import ee.cone.c4actor.Types.{LEvents, NextOffset}
import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol._

import scala.concurrent.{ExecutionContext, Future}

// inner (TxTr-like) handler api
case class RHttpResponse(response: S_HttpResponse, events: LEvents)
object RHttpTypes {
  type RHttpHandler = (S_HttpRequest,Context)=>RHttpResponse
  type RHttpHandlerCreate = RHttpHandler=>RHttpHandler
}

// outer handler api
case class FHttpRequest(method: String, path: String, rawQueryString: Option[String], headers: List[N_Header], body: okio.ByteString)
trait FHttpHandler {
  def handle(request: FHttpRequest): S_HttpResponse
}

trait RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse
  def deferredResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse, events: LEvents): RHttpResponse
}

trait AlienExchangeState extends Product
trait AlienUtil {
  def read(state: AlienExchangeState): (AlienExchangeState, String)
  def send(value: String): AlienExchangeState
  def stop(state: AlienExchangeState): Unit
}
