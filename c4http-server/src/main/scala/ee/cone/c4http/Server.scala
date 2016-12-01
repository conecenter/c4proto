
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
  with QReceiverApp
  with QSenderApp
  with QAdapterRegistryApp
  with HttpContentProviderApp
  with SSEQueueApp
{
  def httpPort: Int = Option(System.getenv("C4HTTP_PORT")).get.toInt
  def ssePort: Int = Option(System.getenv("C4SSE_PORT")).get.toInt


  lazy val  pool: ExecutorService = Pool()


  def httpPostObserver: HttpPostObserver = ???


  def channelStatusObserver: ChannelStatusObserver = ???

  //def commandReceivers: List[Receiver[_]] = ???
  def rawQSender: RawQSender = ???
  def protocols: List[Protocol] = QProtocol :: HttpProtocol :: Nil
  //def dataDependencies: List[DataDependencyTo[_]] = ???
  def worldProvider: WorldProvider = ???
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

/*
class ReceiverToStaticContent(
  reducer: Reducer
) extends CommandReceiver[HttpProtocol.RequestValue] {
  def className: String = classOf[HttpProtocol.RequestValue].getName
  def handle(world: World, command: RequestValue): World = {
    reducer.reduce(world, Map(StaticRootKey→Map(command.path→List(command))))
  }
}

trait ReceiverToStaticContentApp extends CommandReceiversApp {
  def reducer: Reducer
  lazy val receiverToStaticContent: CommandReceiver[HttpProtocol.RequestValue] =
    new ReceiverToStaticContent(reducer)
  override def commandReceivers: List[CommandReceiver[_]] =
    receiverToStaticContent :: super.commandReceivers
}*/

//case object StaticRootKey extends WorldKey[Index[String,HttpProtocol.RequestValue]](Map.empty)

//

trait SSEQueueApp extends CommandReceiversApp {
  def sseServer: TcpServer
  def qSender: QSender
  def sseStatusTopic: TopicName
  lazy val sseEventCommandReceiver: CommandReceiver[_] =
    new SSEEventCommandReceiver(qSender, sseStatusTopic, sseServer)
  lazy val channelStatusObserver: ChannelStatusObserver =
    new SSEChannelStatusObserverImpl(qSender, sseStatusTopic)
  override def commandReceivers: List[CommandReceiver[_]] =
    sseEventCommandReceiver :: super.commandReceivers
}

class SSEEventCommandReceiver(
  qSender: QSender, sseStatusTopic: TopicName, sseServer: TcpServer
) extends CommandReceiver[HttpProtocol.SSEvent] {
  def className: String = classOf[HttpProtocol.SSEvent].getName
  def handle(world: World, command: SSEvent): World = {
    val key = command.connectionKey
    sseServer.senderByKey(key) match {
      case Some(send) ⇒ send.add(command.body.toByteArray)
      case None ⇒ qSender.sendUpdate(sseStatusTopic, "", HttpProtocol.SSEStatus(key, "agent not found"))
    }
    world
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

trait KafkaProducerApp {
  lazy val kafkaProducer: Producer[Array[Byte], Array[Byte]] = Producer() ???
}

class KafkaRawQSender(producer: Producer[Array[Byte], Array[Byte]]) extends RawQSender {
  def send(topic: TopicName, key: Array[Byte], value: Array[Byte]): Unit = {
    producer.send(new ProducerRecord(topic.value, 0, key, value)).get()
  }
}

//

object HttpGateway {
  def main(args: Array[String]): Unit = try {
    val bootstrapServers = Option(System.getenv("C4BOOTSTRAP_SERVERS")).get

    val postTopic = Option(System.getenv("C4HTTP_POST_TOPIC")).getOrElse("http-posts")
    val getTopic = Option(System.getenv("C4HTTP_GET_TOPIC")).getOrElse("http-gets")



    val producer = Producer(bootstrapServers)

    val pool = Pool()
    var world: World = Map.empty
    val reducer = Reducer(handlerLists)
    val consumer =
      new ToStoredConsumer(bootstrapServers, getTopic, 0)(pool, { (messages: Iterable[QRecord]) ⇒

          world = qReceiver.receiveEvents(world, messages)

      })

    ////
    consumer.start()
    server.start()
    sseService.start(handlerLists, ssePort)

    while(consumer.state != Finished) {
      //println(consumer.state)
      Thread.sleep(1000)
    }
  } finally System.exit(0)
}
