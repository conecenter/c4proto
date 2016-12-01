package ee.cone.c4http

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

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

trait SSEServerApp extends ToStartApp {
  def ssePort: Int
  def channelStatusObserver: ChannelStatusObserver
  lazy val sseServer: TcpServer with CanStart =
    new TcpServerImpl(ssePort, channelStatusObserver)
  override def toStart: List[CanStart] = sseServer :: super.toStart
}

class TcpServerImpl(
  port: Int, channelStatusObserver: ChannelStatusObserver
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
          channelStatusObserver.changed(key, Some(error))
          channels -= key
        })
        channelStatusObserver.changed(key, None)
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}

