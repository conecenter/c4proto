package ee.cone.c4http

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4proto.{MessageMapper, QProducerRecord}



////

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
}



trait TcpServer {
  def senderByKey(key: String): Option[SenderToAgent]
  def status(key: String, message: String): QProducerRecord
}

////





////

trait CommandReceiversApp {
  def commandReceivers: List[MessageMapper[_]] = Nil
}