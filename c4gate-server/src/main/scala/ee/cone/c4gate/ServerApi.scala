
package ee.cone.c4gate

import ee.cone.c4actor._

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
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

trait TcpServerConfig {
  def port: Int
  def timeout: Long
}

trait SSEConfig {
  def allowOrigin: Option[String]
  def pongURL: String
  def stateRefreshPeriodSeconds: Int
  def tolerateOfflineSeconds: Int
  def sessionWaitingPosts: Int
}