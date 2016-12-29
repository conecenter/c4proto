
package ee.cone.c4gate

import ee.cone.c4actor.Types.World

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
}

trait TcpServer {
  def senderByKey(key: String): Option[SenderToAgent]
}

trait WorldProvider {
  def createTx(): World
}