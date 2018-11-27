
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
  executor: ScheduledExecutorService, timeout: Long, val compressor: Option[Compressor]
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

class TcpServerImpl(
  port: Int, tcpHandler: TcpHandler, timeout: Long, compressorFactory: StreamCompressorFactory,
  channels: TrieMap[String,ChannelHandler] = TrieMap()
) extends ToInject with Executable with LazyLogging {
  def toInject: List[Injectable] = GetSenderKey.set(channels.get)

  def run(): Unit = concurrent.blocking{
    tcpHandler.beforeServerStart()
    val address = new InetSocketAddress(port)
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
        }, executor, timeout, compressorFactory.create())
        channels += key → sender
        tcpHandler.afterConnect(key, sender)
      }
      def failed(exc: Throwable, att: Unit): Unit = logger.error("tcp",exc) //! may be set status-finished
    })
  }
}
