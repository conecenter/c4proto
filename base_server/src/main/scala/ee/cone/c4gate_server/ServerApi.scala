
package ee.cone.c4gate_server

import ee.cone.c4actor._
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate.HttpProtocol.{S_HttpRequest, S_HttpResponse}
import okio.ByteString

import scala.concurrent.{ExecutionContext, Future}

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close(): Unit
  def compressor: Option[Compressor]
}

case object GetSenderKey extends SharedComponentKey[String=>Option[SenderToAgent]]

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
case class RHttpResponse(instantResponse: Option[S_HttpResponse], events: List[LEvent[Product]])
object RHttpTypes {
  type RHttpHandler = (S_HttpRequest,Context)=>RHttpResponse
  type RHttpHandlerCreate = RHttpHandler=>RHttpHandler
}

// outer handler api
case class FHttpRequest(method: String, path: String, headers: List[N_Header], body: ByteString)
trait FHttpHandler {
  def handle(request: FHttpRequest)(implicit executionContext: ExecutionContext): Future[S_HttpResponse]
}

trait RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse
  def setSession(request: S_HttpRequest, userName: Option[String], was: Option[U_AuthenticatedSession]): RHttpResponse
}

class TxRes[R](val value: R, val next: WorldProvider)
trait WorldProvider {
  def tx[R](f: Context=>(List[LEvent[Product]],R))(implicit executionContext: ExecutionContext): Future[TxRes[R]]
  //def sync(local: Option[Context])(implicit executionContext: ExecutionContext): Future[Context]
}

trait StatefulReceiverFactory {
  def create[Message](inner: List[Observer[Message]])(implicit executionContext: ExecutionContext): Future[StatefulReceiver[Message]]
}
trait StatefulReceiver[Message] {
  def send(message: Message): Unit
}

class DataDir(val value: String)
