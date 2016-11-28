package ee.cone.c4http

import java.io.OutputStream
import java.net.{InetSocketAddress, ServerSocket}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util
import java.util.UUID
import java.util.concurrent.{Executor, ExecutorService, Future}

import ee.cone.base.connection_api._
import ee.cone.base.util.Bytes
import ee.cone.c4proto.{CoHandlerLists, OnShutdown}

import scala.collection.immutable.Queue



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

class ChannelHandler(channel: AsynchronousSocketChannel) extends CompletionHandler[Integer,Unit]{
  def add(data: Array[Byte]): Unit = ???
  def write(data: Array[Byte]): Unit = {
    channel.write[Unit](ByteBuffer.wrap(data), null, this)
  }
  def completed(result: Integer, att: Unit): Unit = ???
  def failed(exc: Throwable, att: Unit): Unit = ???
}

class SSE {
  private var channels: Map[String,ChannelHandler] = Map()
  def startServer(handlerLists: CoHandlerLists, ssePort: Int): Unit = {
    val listener = AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(ssePort))
    listener.accept[Unit](null, new CompletionHandler[AsynchronousSocketChannel,Unit] {
      def completed(ch: AsynchronousSocketChannel, att: Unit): Unit = {
        listener.accept[Unit](null, this)
        val key = UUID.randomUUID.toString
        synchronized {
          channels = channels + ( key → new ChannelHandler(ch))
        }
        //??? publish status true
      }
      def failed(exc: Throwable, att: Unit): Unit = exc.printStackTrace() //! may be set status-finished
    })
  }
  def sendToAgent(connectionKey: String, data: Array[Byte]): Unit = synchronized {
    ???
  }
}

import java.nio.channels.AsynchronousSocketChannel




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
