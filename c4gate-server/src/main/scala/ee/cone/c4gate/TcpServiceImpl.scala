
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

trait SSEServerApp extends ToStartApp with DataDependenciesApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  def indexFactory: IndexFactory

  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  private lazy val sseServer = new TcpServerImpl(ssePort, qMessages, worldProvider)
  private lazy val tcpWriteByConnectionJoin = new TcpWriteByConnectionJoin
  private lazy val tcpConnectionTxTransformJoin = new TcpConnectionTxTransformJoin(sseServer)
  override def toStart: List[Executable] = sseServer :: super.toStart
  override def dataDependencies: List[DataDependencyTo[_]] =
    tcpWriteByConnectionJoin :: tcpConnectionTxTransformJoin ::
    super.dataDependencies
}

class TcpServerImpl(
  port: Int, qMessages: QMessages, worldProvider: WorldProvider,
  channels: TrieMap[String,ChannelHandler] = TrieMap()
) extends TcpServer with Executable {
  def senderByKey(key: String): Option[SenderToAgent] = channels.get(key)
  def run(ctx: ExecutionContext): Unit = {
    Option(worldProvider.createTx())
      .map { local ⇒
        val world = TxKey.of(local).world
        val connections =  By.srcId(classOf[TcpConnection]).of(world).values.flatten
        LEvent.add(connections.map(LEvent.delete))(local)
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
            .map(LEvent.add(Seq(LEvent.delete(TcpConnection(key)))))
            .foreach(qMessages.send)
        }, { error ⇒
          println(error.getStackTrace.toString)
        })
        Option(worldProvider.createTx())
          .map(LEvent.add(Seq(LEvent.update(TcpConnection(key)))))
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
)(tcpServer: TcpServer) extends TxTransform {
  def transform(local: World): World = {
    def sender = tcpServer.senderByKey(connectionKey)
    for(d ← tcpDisconnects; s ← sender) s.close()
    for(message ← writes; s ← sender) s.add(message.body.toByteArray)
    LEvent.add(writes.map(LEvent.delete))(local)
  }
}

case class TcpWriteByConnection(connectionKey: SrcId, write: TcpWrite)

class TcpWriteByConnectionJoin extends Join1(
  By.srcId(classOf[TcpWrite]),
  By.srcId(classOf[TcpWriteByConnection])
){
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(writes: Values[TcpWrite]): Values[(SrcId, TcpWriteByConnection)] =
    writes.flatMap(write⇒withKey(TcpWriteByConnection(write.connectionKey,write)))
  def sort(values: Iterable[TcpWriteByConnection]): List[TcpWriteByConnection] =
    values.toList.sortBy(_.write.priority)
}

class TcpConnectionTxTransformJoin(tcpServer: TcpServer) extends Join3(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[TcpDisconnect]),
  By.srcId(classOf[TcpWriteByConnection]),
  By.srcId(classOf[TxTransform])
) {
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(
      tcpConnections: Values[TcpConnection],
      tcpDisconnects: Values[TcpDisconnect],
      writes: Values[TcpWriteByConnection]
  ) =
    if(Seq(tcpConnections,tcpDisconnects,writes).forall(_.isEmpty)) Nil
    else if(tcpConnections.isEmpty){
      val zombies = tcpDisconnects ++ writes.map(_.write)
      val key = tcpDisconnects.map(_.connectionKey) ++ writes.map(_.connectionKey)
      withKey[Product with TxTransform](SimpleTxTransform(key.head, zombies.map(LEvent.delete)))
    }
    else withKey[Product with TxTransform](TcpConnectionTxTransform(
      Single(tcpConnections).connectionKey, tcpDisconnects, writes.map(_.write)
    )(tcpServer))
  def sort(nodes: Iterable[TxTransform]) = Single.list(nodes.toList)
}