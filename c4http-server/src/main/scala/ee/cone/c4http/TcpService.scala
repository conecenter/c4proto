package ee.cone.c4http

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.UUID

import ee.cone.c4proto.Types.{Index, World}
import ee.cone.c4proto._

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Queue

class ChannelHandler(
  channel: AsynchronousSocketChannel, fail: Throwable⇒Unit
) extends CompletionHandler[Integer,Unit]{
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

class TcpService {
  private val channels = TrieMap[String,ChannelHandler]()
  def start(handlerLists: CoHandlerLists, port: Int): Unit = {
    val address = new InetSocketAddress(port)
    val listener = AsynchronousServerSocketChannel.open().bind(address)
    listener.accept[Unit](null, new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = {
        listener.accept[Unit](null, this)
        val key = UUID.randomUUID.toString
        channels += key → new ChannelHandler(ch, error ⇒ {
          val message = error.getStackTrace.toString
          handlerLists.list(ChannelStatusKey).foreach(_(ChannelFailed(key, message)))
          channels -= key
        })
        handlerLists.list(ChannelStatusKey).foreach(_(ChannelCreated(key)))
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
  def handlers: List[BaseCoHandler] = List(
    CoHandler(SenderToAgentKey)(key⇒channels.get(key).map(handler⇒handler.add))
  )
}


case object SenderToAgentKey extends EventKey[String⇒Option[Array[Byte]⇒Unit]]

trait ChannelStatus
case class ChannelCreated(key: String) extends ChannelStatus
case class ChannelFailed(key: String, message: String) extends ChannelStatus
case object ChannelStatusKey extends EventKey[ChannelStatus⇒Unit]

/*
private def sendToAgent(handlerLists: CoHandlerLists, key: String, data: Array[Byte]): Unit =
  channels.get(key) match {
    case Some(handler) ⇒ handler.add(data)
    case None ⇒
      handlerLists.list(ChannelStatusKey).foreach(_(ChannelNotFound(key)))
  }*/

/*
class SSESender(
  allowOriginOption: Option[String]
) extends SenderOfConnection with CoHandlerProvider {
  private var outOpt: Option[OutputStream] = None
  private def out: OutputStream = outOpt.get
  def handlers = CoHandler(SetOutput){ out => outOpt = Option(out) } :: Nil
  private lazy val connected = {
    val allowOrigin =
      allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    out.write(Bytes(s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"))
  }
  def sendToAlien(event: String, data: String) = {
    connected
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    out.write(Bytes(s"event: $event\ndata: $escapedData\n\n"))
    out.flush()
    println(s"event: $event\ndata: $escapedData\n\n")
  }
}

class RSSEServer(
  ssePort: Int,
  lifeCycleManager: ExecutionManager,
  createConnection: LifeCycle ⇒ CoMixBase
) extends CanStart {
  private lazy val serverSocket = new ServerSocket(ssePort) //todo toClose
  def start() = lifeCycleManager.submit{ ()=>
    while(true){
      val socket = serverSocket.accept()
      lifeCycleManager.startConnection{ lifeCycle =>
        lifeCycle.onClose(()=>socket.close())
        val out = socket.getOutputStream
        lifeCycle.onClose(()=>out.close())

        val connection = createConnection(lifeCycle)
        connection.handlerLists.list(SetOutput).foreach(_(out))
        connection
      }
    }
  }
}
*/
/*
class SeverSSE(ssePort: Int) extends Runnable {
  def run(): Unit = {
    val serverSocket = new ServerSocket(ssePort)
    OnShutdown(()⇒serverSocket.close())
    while(true){
      val socket = serverSocket.accept()
      socket.
    }
  }
}
*/

////////////////////////




///////////////////////////
/*
class SerialExecutor(
  executor: ExecutorService, add: Runnable⇒Unit, poll: ()⇒Option[Runnable]
) {
  private var tasks: Queue[()⇒Unit] = Queue.empty
  private var active: Option[Future[_]] = None
  def failed: Boolean = synchronized {
    active.exists(_.isDone)
  }
  def execute(add: Queue[()⇒Unit]⇒Queue[()⇒Unit]): Unit = synchronized {
    tasks = add(tasks)
    if(active.isEmpty) scheduleNext()
  }
  private def scheduleNext(): Unit = synchronized {
    tasks.dequeueOption.foreach{ case (task, more) ⇒
      tasks = more
      active = Option(executor.submit(new Runnable() {
        def run(): Unit = {
          task()
          scheduleNext()
        }
      }))
    }
  }
}
*/