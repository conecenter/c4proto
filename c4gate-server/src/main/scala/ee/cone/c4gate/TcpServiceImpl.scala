
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Queue

class ChannelHandler(
  channel: AsynchronousSocketChannel, unregister: ()⇒Unit, fail: Throwable⇒Unit
) extends CompletionHandler[Integer,Unit] with SenderToAgent {
  private var queue: Queue[Array[Byte]] = Queue.empty
  private var activeElement: Option[ByteBuffer] = None
  private def startWrite(): Unit =
    queue.dequeueOption.foreach{ case (element,nextQueue) ⇒
      queue = nextQueue
      activeElement = Option(ByteBuffer.wrap(element))
      channel.write[Unit](activeElement.get, (), this)
    }
  def add(data: Array[Byte]): Unit = synchronized {
    queue = queue.enqueue(data)
    if(activeElement.isEmpty) startWrite()
  }
  def completed(result: Integer, att: Unit): Unit = Trace {
    synchronized {
      if(activeElement.get.hasRemaining) channel.write[Unit](activeElement.get, (), this)
      else {
        activeElement = None
        startWrite()
      }
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

class TcpServerImpl(
  port: Int, tcpHandler: TcpHandler,
  channels: TrieMap[String,ChannelHandler] = TrieMap()
) extends InitLocal with Executable {
  def initLocal: World ⇒ World = GetSenderKey.set(channels.get)

  def run(): Unit = concurrent.blocking{
    tcpHandler.beforeServerStart()
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = Trace {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        val sender = new ChannelHandler(ch, {() ⇒
          channels -= key
          tcpHandler.afterDisconnect(key)
        }, { error ⇒
          println(error.getStackTrace.toString)
        })
        channels += key → sender
        tcpHandler.afterConnect(key, sender)
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}
