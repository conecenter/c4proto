
package ee.cone.c4gate

import ee.cone.c4actor.ActorName

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
}

trait TcpServer {
  def senderByKey(key: String): Option[SenderToAgent]
  def targets: List[ActorName]
}

trait ForwarderConfig {
  def targets(path: String): List[ActorName]
}