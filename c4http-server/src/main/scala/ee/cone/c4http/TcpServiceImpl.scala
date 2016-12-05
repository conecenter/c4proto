package ee.cone.c4http

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID
import java.util.concurrent.ExecutorService

import ee.cone.c4http.TcpProtocol._
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
      channel.write[Unit](ByteBuffer.wrap(element), (), this)
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

trait SSEServerApp extends ToStartApp with MessageMappersApp with ProtocolsApp {
  def ssePort: Int
  def qMessages: QMessages
  def sseStatusStreamKey: StreamKey
  def sseEventStreamKey: StreamKey
  def rawQSender: RawQSender

  lazy val sseServer: TcpServer with CanStart =
    new TcpServerImpl(ssePort, qMessages, sseStatusStreamKey, rawQSender)
  override def toStart: List[CanStart] = sseServer :: super.toStart
  lazy val sseEventCommandReceiver: MessageMapper[_] =
    new SSEEventCommandMapper(sseEventStreamKey, sseServer)
  override def messageMappers: List[MessageMapper[_]] =
    sseEventCommandReceiver :: super.messageMappers
  override def protocols: List[Protocol] = TcpProtocol :: super.protocols
}

class TcpServerImpl(
  port: Int, qMessages: QMessages, sseStatusStream: StreamKey, rawQSender: RawQSender
) extends TcpServer with CanStart {
  val channels: TrieMap[String,ChannelHandler] = TrieMap()
  def senderByKey(key: String): Option[SenderToAgent] = channels.get(key)
  def early: Option[ShouldStartEarly] = None
  def start(pool: ExecutorService): Unit = {
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        channels += key → new ChannelHandler(ch, error ⇒ {
          rawQSender.send(sseStatusStream, status(key, error.getStackTrace.toString))
          channels -= key //close?
        })
        rawQSender.send(sseStatusStream, status(key, ""))
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
  def status(key: String, message: String): QMessage =
    QMessage(Status(key, message))
}

class SSEEventCommandMapper(
  streamKey: StreamKey,
  sseServer: TcpServer
) extends MessageMapper[WriteEvent](streamKey, classOf[WriteEvent]) {
  def mapMessage(command: WriteEvent): Seq[QProducerRecord] = {
    val key = command.connectionKey
    sseServer.senderByKey(key) match {
      case Some(send) ⇒
        send.add(command.body.toByteArray)
        Nil
      case None ⇒ sseServer.status(key, "agent not found") :: Nil
    }
  }
}
