
package ee.cone.c4gate

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.Header

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
  def compressor: Option[Compressor]
}

trait WorldProvider {
  def createTx(): Context
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
  def handle(httpExchange: HttpExchange, headers: List[Header]): Boolean
}