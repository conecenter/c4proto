package ee.cone.c4http

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
}

trait TcpServer {
  def senderByKey(key: String): Option[SenderToAgent]
}
