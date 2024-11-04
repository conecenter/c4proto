
package ee.cone.c4gate_server

import ee.cone.c4actor.Types.LEvents
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
  def handle(request: FHttpRequest)(implicit executionContext: ExecutionContext): Future[S_HttpResponse]
}

trait RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse
  def deferredResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse, events: LEvents): RHttpResponse
}

object WorldProvider{
  sealed trait Ctl
  case class Next(events: LEvents) extends Ctl
  case class Redo() extends Ctl
  case class Stop() extends Ctl
  type Steps = List[AssembledContext=>Ctl]
}
trait WorldProvider {
  import WorldProvider._
  def run(steps: Steps): Unit
}

trait EventLogReader {
  def read(logKey: String, pos: Long): (Long, String)
}

trait FromAlienUpdaterFactory {
  def create(): FromAlienUpdater
}
trait FromAlienUpdater {
  def send(was: Long, value: String)(implicit executionContext: ExecutionContext): Future[Long]
  def stop()(implicit executionContext: ExecutionContext): Future[Unit]
}

