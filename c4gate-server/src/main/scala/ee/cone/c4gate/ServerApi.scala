
package ee.cone.c4gate

import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
}

trait WorldProvider {
  def createTx(): World
}

case object GetSenderKey extends WorldKey[String⇒Option[SenderToAgent]](_⇒throw new Exception)

trait TcpHandler {
  def beforeServerStart(): Unit
  def afterConnect(key: String, sender: SenderToAgent): Unit
  def afterDisconnect(key: String): Unit
}

trait SSEConfig {
  def allowOrigin: Option[String]
  def pongURL: String
}