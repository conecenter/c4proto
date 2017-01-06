
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Values, World}

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

trait SSEServerApp extends ToStartApp with AssemblesApp with InitLocalsApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider

  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  private lazy val sseServer = new TcpServerImpl(ssePort, qMessages, worldProvider)
  override def toStart: List[Executable] = sseServer :: super.toStart
  override def assembles: List[Assemble] = new TcpAssemble :: super.assembles
  override def initLocals: List[InitLocal] = sseServer :: super.initLocals
}

case object GetSenderKey extends WorldKey[String⇒Option[SenderToAgent]](_⇒None)

class TcpServerImpl(
  port: Int, qMessages: QMessages, worldProvider: WorldProvider,
  channels: TrieMap[String,ChannelHandler] = TrieMap()
) extends InitLocal with Executable {
  def initLocal: World ⇒ World = GetSenderKey.set(channels.get)
  def run(ctx: ExecutionContext): Unit = {
    Option(worldProvider.createTx())
      .map { local ⇒
        val world = TxKey.of(local).world
        val connections =  By.srcId(classOf[TcpConnection]).of(world).values.flatten.toSeq
        LEvent.add(connections.flatMap(LEvent.delete))(local)
      }
      .foreach(qMessages.send)

    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit]((), new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = Trace {
        listener.accept[Unit]((), this)
        val key = UUID.randomUUID.toString
        channels += key → new ChannelHandler(ch, {() ⇒
          channels -= key
          Option(worldProvider.createTx())
            .map(LEvent.add(LEvent.delete(TcpConnection(key))))
            .foreach(qMessages.send)
        }, { error ⇒
          println(error.getStackTrace.toString)
        })
        Option(worldProvider.createTx())
          .map(LEvent.add(LEvent.update(TcpConnection(key))))
          .foreach(qMessages.send)
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
}

case class TcpConnectionTxTransform(
  connectionKey: SrcId,
  tcpDisconnects: Values[TcpDisconnect],
  writes: Values[TcpWrite]
) extends TxTransform {
  def transform(local: World): World = {
    def sender = GetSenderKey.of(local)(connectionKey)
    for(d ← tcpDisconnects; s ← sender) s.close()
    for(message ← writes; s ← sender) s.add(message.body.toByteArray)
    LEvent.add(writes.flatMap(LEvent.delete))(local)
  }
}

@assemble class TcpAssemble extends Assemble {
  type ConnectionKey = SrcId
  def joinTcpWrite(key: SrcId, writes: Values[TcpWrite]): Values[(ConnectionKey, TcpWrite)] =
    writes.map(write⇒write.connectionKey→write)
  def joinTxTransform(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect],
    @by[ConnectionKey] writes: Values[TcpWrite]
  ): Values[(SrcId,TxTransform)] = List(key → (
    if(tcpConnections.isEmpty)
      SimpleTxTransform((tcpDisconnects ++ writes).take(4096).flatMap(LEvent.delete))
    else TcpConnectionTxTransform(key, tcpDisconnects, writes.sortBy(_.priority))
  ))
}
