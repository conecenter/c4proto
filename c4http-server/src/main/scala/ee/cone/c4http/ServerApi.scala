package ee.cone.c4http

import ee.cone.c4proto.QMessage

////

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
}

trait TcpServer {
  def senderByKey(key: String): Option[SenderToAgent]
  def status(key: String, message: String): QMessage
}
