
package ee.cone.c4http

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4http.HttpProtocol.{RequestValue, SSEvent}
import ee.cone.c4proto.Types.{Index, Values, World}
import ee.cone.c4proto._
import org.apache.kafka.clients.producer.{Producer, ProducerRecord}
import org.apache.kafka.clients.producer.internals.Sender

import scala.collection.concurrent.TrieMap


object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}



////



class HttpGatewayApp
  extends IndexFactoryApp
  with ReducerApp
  with DataDependenciesApp
  with ProtocolDataDependenciesApp
  with HttpServerApp
  with SSEServerApp
  with QMessageReceiverApp
  with QStatePartReceiverApp
  with QSenderApp
  with QAdapterRegistryApp
  with PoolApp
  with ToIdempotentConsumerApp
  with ToStoredConsumerApp
  with HttpContentProviderApp
  with SSEQueueApp
{
  def bootstrapServers: String = Option(System.getenv("C4BOOTSTRAP_SERVERS")).get
  def httpPort: Int = Option(System.getenv("C4HTTP_PORT")).get.toInt
  def ssePort: Int = Option(System.getenv("C4SSE_PORT")).get.toInt
  def sseStatusTopic: TopicName = TopicName("sse-status")




  def httpPostObserver: HttpPostObserver = ???

  //def commandReceivers: List[Receiver[_]] = ???
  def rawQSender: RawQSender = ???
  def protocols: List[Protocol] = QProtocol :: HttpProtocol :: Nil
  //def dataDependencies: List[DataDependencyTo[_]] = ???
  def worldProvider: WorldProvider = ???
  def messageConsumerTopic: TopicName = ???
  def consumerGroupId: String = ???

  def statePartConsumerTopic: TopicName = ???
}

////

trait WorldProvider {
  def value: World
}

//

trait HttpContentProviderApp {
  def worldProvider: WorldProvider
  lazy val httpContentProvider: HttpContentProvider =
    new HttpContentProviderImpl(worldProvider)
}

class HttpContentProviderImpl(
  worldProvider: WorldProvider
) extends HttpContentProvider {
  def get(path: String): List[HttpProtocol.RequestValue] =
    By.srcId(classOf[HttpProtocol.RequestValue]).of(worldProvider.value).getOrElse(path, Nil)
}

trait SSEQueueApp extends CommandReceiversApp {
  def sseServer: TcpServer
  def qSender: QSender
  def sseStatusTopic: TopicName
  lazy val sseEventCommandReceiver: MessageReceiver[_] =
    new SSEEventCommandReceiver(qSender, sseStatusTopic, sseServer)
  lazy val channelStatusObserver: ChannelStatusObserver =
    new SSEChannelStatusObserverImpl(qSender, sseStatusTopic)
  override def commandReceivers: List[MessageReceiver[_]] =
    sseEventCommandReceiver :: super.commandReceivers
}

class SSEEventCommandReceiver(
  qSender: QSender, sseStatusTopic: TopicName, sseServer: TcpServer
) extends MessageReceiver[HttpProtocol.SSEvent] {
  def className: String = classOf[HttpProtocol.SSEvent].getName
  def receiveMessage(command: SSEvent): Unit = {
    val key = command.connectionKey
    sseServer.senderByKey(key) match {
      case Some(send) ⇒ send.add(command.body.toByteArray)
      case None ⇒ qSender.sendUpdate(sseStatusTopic, "", HttpProtocol.SSEStatus(key, "agent not found"))
    }
  }
}

class SSEChannelStatusObserverImpl(
  qSender: QSender, sseStatusTopic: TopicName
) extends ChannelStatusObserver {
  def changed(key: String, error: Option[Throwable]): Unit = {
    val message = error.map(_.getStackTrace.toString).getOrElse("")
    qSender.sendUpdate(sseStatusTopic, "", HttpProtocol.SSEStatus(key, message))
  }
}



// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content

object HttpGateway {
  def main(args: Array[String]): Unit = try {
    val app = new HttpGatewayApp
    app.pool.start()
    val consumer = app.toStoredConsumer
    consumer.start()
    while(consumer.state < ConsumerState.started) {
      //println(consumer.state)
      Thread.sleep(1000)
    }
    app.toStart.foreach(_.start())
    //val postTopic = Option(System.getenv("C4HTTP_POST_TOPIC")).getOrElse("http-posts")
    //val getTopic = Option(System.getenv("C4HTTP_GET_TOPIC")).getOrElse("http-gets")
    while(consumer.state < ConsumerState.finished) {
      //println(consumer.state)
      Thread.sleep(1000)
    }
  } finally System.exit(0)
}
