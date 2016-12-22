
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4actor.Types.{SrcId, Values, World}
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
  def failed(exc: Throwable, att: Unit): Unit = {
    fail(exc)
    close()
  }
  def close(): Unit = {
    unregister()
    channel.close()  //does close block?
  }
}

trait SSEServerApp extends ToStartApp with TxTransformsApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider

  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  lazy val sseServer: TcpServer with Executable =
    new TcpServerImpl(ssePort, qMessages, worldProvider)
  lazy val tcpTxTransform = new TcpTxTransform(sseServer)
  override def txTransforms: List[TxTransform] = tcpTxTransform :: super.txTransforms
  override def toStart: List[Executable] = sseServer :: super.toStart
}

class TcpServerImpl(
  port: Int, qMessages: QMessages, worldProvider: WorldProvider,
  channels: TrieMap[String,ChannelHandler] = TrieMap()
) extends TcpServer with Executable {
  def senderByKey(key: String): Option[SenderToAgent] = channels.get(key)
  def run(ctx: ExecutionContext): Unit = {
    val tx = worldProvider.createTx()
    qMessages.send(tx.add(
      By.srcId(classOf[TcpConnection]).of(tx.world).values.flatten.map(LEvent.delete).toSeq:_*
    ))
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = Trace {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        channels += key → new ChannelHandler(ch, {() ⇒
          channels -= key
          val tx = worldProvider.createTx()
          qMessages.send(tx.add(LEvent.delete(TcpConnection(key))))
        }, { error ⇒
          println(error.getStackTrace.toString)
        })
        val tx = worldProvider.createTx()
        qMessages.send(tx.add(LEvent.update(TcpConnection(key))))
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}

class TcpTxTransform(sseServer: TcpServer) extends TxTransform {
  def transform(tx: WorldTx): WorldTx = {
    val writes = By.srcId(classOf[TcpWrite]).of(tx.world).values.flatten.toSeq
    val disconnects = By.srcId(classOf[TcpDisconnect]).of(tx.world).values.flatten.toSeq
    writes.sortBy(_.priority).foreach { message ⇒
      val sender = sseServer.senderByKey(message.connectionKey)
      sender.foreach(s⇒s.add(message.body.toByteArray))
    }
    disconnects.map(_.connectionKey).flatMap(sseServer.senderByKey).foreach(_.close())
    tx.add(writes.map(LEvent.delete) ++ disconnects.map(LEvent.delete):_*)
  }
}
