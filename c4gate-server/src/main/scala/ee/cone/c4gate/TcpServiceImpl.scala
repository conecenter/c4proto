
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4actor.Types.World
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4actor._

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Queue

class ChannelHandler(
  channel: AsynchronousSocketChannel, unregister: ()⇒Unit, fail: Throwable⇒Unit
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
    queue = queue.enqueue(data)
    if(activeElement.isEmpty) startWrite()
  }
  def completed(result: Integer, att: Unit): Unit = Trace {
    synchronized {
      activeElement = None
      startWrite()
    }
  }
  def failed(exc: Throwable, att: Unit): Unit = fail(exc)
  def close(): Unit = {
    unregister()
    channel.close()  //does close block?
  }
}

trait SSEServerApp extends ToStartApp {
  def ssePort: Int
  def qMessages: QMessages
  def reducer: Reducer
  def worldProvider: WorldProvider

  lazy val sseServer: TcpServer with Executable =
    new TcpServerImpl(ssePort, qMessages, reducer, ()⇒worldProvider.world)
  override def toStart: List[Executable] = sseServer :: super.toStart
  private lazy val sseWriteEventCommandMapper =
    new WriteEventCommandHandler(sseServer)
  private lazy val sseDisconnectEventCommandMapper =
    new TcpStatusCommandHandler(sseServer)
}

class TcpServerImpl(
  port: Int, qMessages: QMessages, reducer: Reducer, getWorld: ()⇒World
) extends TcpServer with Executable {
  val channels: TrieMap[String,ChannelHandler] = TrieMap()
  def senderByKey(key: String): Option[SenderToAgent] = channels.get(key)
  def run(ctx: ExecutionContext): Unit = {
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = Trace {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        channels += key → new ChannelHandler(ch, () ⇒ channels -= key, { error ⇒
          println(error.getStackTrace.toString)
        })
        qMessages.send(reducer.createTx(getWorld()).add(LEvent.update(key, TcpConnected(key))))
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}

class WriteEventCommandHandler(sseServer: TcpServer) extends MessageHandler(classOf[TcpWrite]) {
  def handleMessage(message: TcpWrite): Unit = {
    sseServer.senderByKey(message.connectionKey).get.add(message.body.toByteArray)
  }
}

class TcpStatusCommandHandler(sseServer: TcpServer) extends MessageHandler(classOf[TcpDisconnect]) {
  def handleMessage(message: TcpDisconnect): Unit = {
    sseServer.senderByKey(message.connectionKey).foreach(sender⇒sender.close())
  }
}
