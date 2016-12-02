package ee.cone.c4http

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4http.HttpProtocol.SSEvent
import ee.cone.c4proto._

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Queue

class ChannelHandler(
  channel: AsynchronousSocketChannel, fail: Throwable⇒Unit
) extends CompletionHandler[Integer,Unit] with SenderToAgent {
  private var queue: Queue[Array[Byte]] = Queue.empty
  private var activeElement: Option[Array[Byte]] = None
  private def startWrite(): Unit =
    queue.dequeueOption.foreach{ case (element,nextQueue) ⇒
      queue = nextQueue
      activeElement = Option(element)
      channel.write[Unit](ByteBuffer.wrap(element), null, this)
    }
  def add(data: Array[Byte]): Unit = synchronized {
    queue.enqueue(data)
    if(activeElement.isEmpty) startWrite()
  }
  def completed(result: Integer, att: Unit): Unit = synchronized {
    activeElement = None
    startWrite()
  }
  def failed(exc: Throwable, att: Unit): Unit = fail(exc)
}

trait SSEServerApp extends ToStartApp with CommandReceiversApp {
  def ssePort: Int
  def qMessages: QMessages
  def sseStatusTopic: TopicName
  def sseEventTopic: TopicName
  def rawQSender: RawQSender

  lazy val sseServer: TcpServer with CanStart =
    new TcpServerImpl(ssePort, qMessages, sseStatusTopic, rawQSender)
  override def toStart: List[CanStart] = sseServer :: super.toStart
  lazy val sseEventCommandReceiver: MessageMapper[_] =
    new SSEEventCommandMapper(sseEventTopic, sseServer)
  override def commandReceivers: List[MessageMapper[_]] =
    sseEventCommandReceiver :: super.commandReceivers
}

class TcpServerImpl(
  port: Int, qMessages: QMessages, sseStatusTopic: TopicName, rawQSender: RawQSender
) extends TcpServer with CanStart {
  val channels: TrieMap[String,ChannelHandler] = TrieMap()
  def senderByKey(key: String): Option[SenderToAgent] = channels.get(key)
  def start(): Unit = {
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit](null, new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = {
        listener.accept[Unit](null, this)
        val key = UUID.randomUUID.toString
        channels += key → new ChannelHandler(ch, error ⇒ {
          rawQSender.send(status(key, error.getStackTrace.toString))
          channels -= key //close?
        })
        rawQSender.send(status(key, ""))
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
  def status(key: String, message: String): QProducerRecord =
    qMessages.update(sseStatusTopic, "", HttpProtocol.SSEStatus(key, message))
}

class SSEEventCommandMapper(
  topicName: TopicName,
  sseServer: TcpServer
) extends MessageMapper[HttpProtocol.SSEvent](topicName, classOf[HttpProtocol.SSEvent]) {
  def mapMessage(command: SSEvent): Seq[QProducerRecord] = {
    val key = command.connectionKey
    sseServer.senderByKey(key) match {
      case Some(send) ⇒
        send.add(command.body.toByteArray)
        Nil
      case None ⇒ sseServer.status(key, "agent not found") :: Nil
    }
  }
}
