package ee.cone.c4http

import com.sun.net.httpserver.HttpExchange
import ee.cone.c4proto.MessageReceiver



////

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
}

trait ChannelStatusObserver {
  def changed(key: String, error: Option[Throwable]): Unit
}

trait TcpServer {
  def senderByKey(key: String): Option[SenderToAgent]
}

////

trait HttpPostObserver {
  def received(req: HttpProtocol.RequestValue): Unit
}

trait HttpContentProvider {
  def get(path: String): List[HttpProtocol.RequestValue]
}

////

trait CommandReceiversApp {
  def commandReceivers: List[MessageReceiver[_]] = Nil
}