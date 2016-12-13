package ee.cone.c4http

import ee.cone.c4proto.ActorName

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