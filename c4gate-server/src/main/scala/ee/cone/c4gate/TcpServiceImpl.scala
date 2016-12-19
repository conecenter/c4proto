
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

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

trait SSEServerApp extends ToStartApp with MessageMappersApp {
  def ssePort: Int
  def internetForwarderConfig: ForwarderConfig
  def qMessages: QMessages
  def sseActorName: ActorName

  lazy val sseServer: TcpServer with Executable =
    new TcpServerImpl(ssePort, qMessages, sseActorName)
  override def toStart: List[Executable] = sseServer :: super.toStart
  private lazy val sseWriteEventCommandMapper =
    new WriteEventCommandMapper(sseServer)
  private lazy val sseDisconnectEventCommandMapper =
    new TcpStatusCommandMapper(sseServer, internetForwarderConfig)
  override def messageMappers: List[MessageMapper[_]] =
    sseWriteEventCommandMapper :: sseDisconnectEventCommandMapper :: super.messageMappers
}

class TcpServerImpl(
  port: Int, qMessages: QMessages, actorName: ActorName
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
          qMessages.send(LEvent.delete(actorName, key, classOf[TcpStatus]))
        })
        qMessages.send(LEvent.update(actorName, key, TcpStatus(key)))
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}

class WriteEventCommandMapper(sseServer: TcpServer) extends MessageMapper(classOf[TcpWrite]) {
  def mapMessage(res: MessageMapping, message: LEvent[TcpWrite]): MessageMapping = {
    val key = message.srcId
    sseServer.senderByKey(key) match {
      case Some(sender) ⇒
        sender.add(message.value.get.body.toByteArray)
        res
      case None ⇒ res.add(LEvent.delete(res.topicName,key,classOf[TcpStatus]))
    }
  }
}

class TcpStatusCommandMapper(sseServer: TcpServer, forwarder: ForwarderConfig) extends MessageMapper(classOf[TcpStatus]) {
  def mapMessage(res: MessageMapping, message: LEvent[TcpStatus]): MessageMapping = {
    if(message.value.isEmpty)
      sseServer.senderByKey(message.srcId).foreach(sender⇒sender.close())
    (res.add(message) /: forwarder.targets(":sse"))((res,actorName) ⇒
      res.add(message.copy(to=InboxTopicName(actorName)))
    )
  }
}
