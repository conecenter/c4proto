
package ee.cone.c4gate_server

import ee.cone.c4actor._
import ee.cone.c4actor.Types._
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate.HttpProtocol.{S_HttpRequest, S_HttpResponse}
import okio.ByteString

import scala.concurrent.{ExecutionContext, Future}

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close(): Unit
  def compressor: Option[RawCompressor]
}

trait TcpServer {
  def getSender(connectionKey: String): Option[SenderToAgent]
}

trait TcpHandler {
  def beforeServerStart(): Unit
  def afterConnect(key: String, sender: SenderToAgent): Unit
  def afterDisconnect(key: String): Unit
}

trait SSEConfig {
  def allowOrigin: Option[String]
  def pongURL: String
  def stateRefreshPeriodSeconds: Int
  def tolerateOfflineSeconds: Int
  def sessionWaitingRequests: Int
}

// inner (TxTr-like) handler api
case class RHttpResponse(response: S_HttpResponse, events: LEvents)
object RHttpTypes {
  type RHttpHandler = (S_HttpRequest,Context)=>RHttpResponse
  type RHttpHandlerCreate = RHttpHandler=>RHttpHandler
}

// outer handler api
case class FHttpRequest(method: String, path: String, rawQueryString: Option[String], headers: List[N_Header], body: ByteString)
trait FHttpHandler {
  def handle(request: FHttpRequest): S_HttpResponse
}

trait RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse
  def deferredResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse, events: LEvents): RHttpResponse
  def setSession(request: S_HttpRequest, userName: Option[String], was: Option[U_AuthenticatedSession]): RHttpResponse
}
