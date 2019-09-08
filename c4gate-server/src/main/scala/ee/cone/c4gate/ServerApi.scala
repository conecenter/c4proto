
package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.N_Header
import okio.ByteString

import scala.concurrent.{ExecutionContext, Future}

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
  def compressor: Option[Compressor]
}

trait WorldProvider {
  def createTx(implicit executionContext: ExecutionContext): Future[Context]
}

case object GetSenderKey extends SharedComponentKey[String⇒Option[SenderToAgent]]

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
  def sessionWaitingPosts: Int
}

trait RHttpHandler {
  def handle(request: RHttpRequest)(implicit executionContext: ExecutionContext): Future[RHttpResponse]
}
case class RHttpRequest(method: String, path: String, headers: List[N_Header], body: ByteString)
case class RHttpResponse(status: Long, headers: List[N_Header], body: ByteString)

