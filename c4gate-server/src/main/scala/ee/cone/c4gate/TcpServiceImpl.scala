
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4actor.Types.World
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
  def failed(exc: Throwable, att: Unit): Unit = {
    fail(exc)
    close()
  }
  def close(): Unit = {
    unregister()
    channel.close()  //does close block?
  }
}

trait SSEServerApp extends ToStartApp with MessageMappersApp {
  def ssePort: Int
  def internetForwarderConfig: ForwarderConfig
  def qMessages: QMessages

  lazy val sseServer: TcpServer with Executable =
    new TcpServerImpl(ssePort, internetForwarderConfig, qMessages)
  override def toStart: List[Executable] = sseServer :: super.toStart
  private lazy val sseWriteEventCommandMapper =
    new WriteEventCommandMapper(sseServer)
  private lazy val sseDisconnectEventCommandMapper =
    new DisconnectEventCommandMapper(sseServer)
  override def messageMappers: List[MessageMapper[_]] =
    sseWriteEventCommandMapper :: sseDisconnectEventCommandMapper :: super.messageMappers
}

class TcpServerImpl(
  port: Int, forwarder: ForwarderConfig, qMessages: QMessages
) extends TcpServer with Executable {
  val channels: TrieMap[String,ChannelHandler] = TrieMap()
  def senderByKey(key: String): Option[SenderToAgent] = channels.get(key)
  def targets: List[ActorName] = forwarder.targets(":sse")
  def run(ctx: ExecutionContext): Unit = {
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = Trace {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        def send(status: String) =
          targets.foreach(actorName ⇒ qMessages.send(Send(actorName, TcpStatus(key, status))))
        channels += key → new ChannelHandler(ch, () ⇒ channels -= key,
          error ⇒ send(error.getStackTrace.toString)
        )
        send("")
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}

class WriteEventCommandMapper(sseServer: TcpServer) extends MessageMapper(classOf[TcpWrite]) {
  def mapMessage(world: World, command: TcpWrite): Seq[MessageMapResult] = {
    val key = command.connectionKey
    sseServer.senderByKey(key) match {
      case Some(sender) ⇒
        sender.add(command.body.toByteArray)
        Nil
      case None ⇒
        sseServer.targets.map(Send(_,TcpStatus(key, "agent not found")))
    }
  }
}

class DisconnectEventCommandMapper(sseServer: TcpServer) extends MessageMapper(classOf[TcpDisconnect]) {
  def mapMessage(world: World, command: TcpDisconnect): Seq[MessageMapResult] = {
    val key = command.connectionKey
    sseServer.senderByKey(key) match {
      case Some(sender) ⇒
        sender.close()
        Nil
      case None ⇒ Nil
    }
  }
}
