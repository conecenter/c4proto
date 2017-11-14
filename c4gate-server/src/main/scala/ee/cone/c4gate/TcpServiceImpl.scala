
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID
import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Queue

class ChannelHandler(
  channel: AsynchronousSocketChannel, unregister: ()⇒Unit, fail: Throwable⇒Unit,
  executor: ScheduledExecutorService, timeout: Long
) extends CompletionHandler[Integer,Unit] with SenderToAgent {
  private var queue: Queue[Array[Byte]] = Queue.empty
  private var activeElement: Option[ByteBuffer] = None
  private var purge: Option[ScheduledFuture[_]] = None
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
        purge.foreach(_.cancel(false))
        purge = Option(executor.schedule(new Runnable {
          def run(): Unit = close()
        },timeout,TimeUnit.SECONDS))
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

@c4component @listed case class TcpServerInject(client: TcpServer) extends ToInject {
  override def toInject: List[Injectable] = client.toInject
}

@c4component @listed case class TcpServerExecutable(client: TcpServer) extends Executable {
  def run(): Unit = client.run()
}

trait TcpServer extends Executable {
  def toInject: List[Injectable]
}

@c4component case class TcpServerImpl(
  config: TcpServerConfig, tcpHandler: TcpHandler
)(
  channels: TrieMap[String,ChannelHandler] = TrieMap()
) extends TcpServer with LazyLogging {
  def toInject: List[Injectable] = GetSenderKey.set(channels.get)

  def run(): Unit = concurrent.blocking{
    tcpHandler.beforeServerStart()
    val address = new InetSocketAddress(config.port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    val executor = Executors.newScheduledThreadPool(1)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = Trace {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        val sender = new ChannelHandler(ch, {() ⇒
          channels -= key
          tcpHandler.afterDisconnect(key)
        }, { error ⇒
          logger.error("channel",error)
        }, executor, config.timeout)
        channels += key → sender
        tcpHandler.afterConnect(key, sender)
      }
      def failed(exc: Throwable, att: Unit): Unit = logger.error("tcp",exc) //! may be set status-finished
    })
  }
}
