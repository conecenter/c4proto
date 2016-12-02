
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
  with QMessageMapperApp
  with QStatePartReceiverApp
  with QMessagesApp
  with QAdapterRegistryApp
  with PoolApp
  with ToIdempotentConsumerApp
  with ToStoredConsumerApp
  with KafkaProducerApp
{
  def bootstrapServers: String = Option(System.getenv("C4BOOTSTRAP_SERVERS")).get
  def httpPort: Int = Option(System.getenv("C4HTTP_PORT")).get.toInt
  def ssePort: Int = Option(System.getenv("C4SSE_PORT")).get.toInt
  def messageConsumerTopic: TopicName = TopicName("http-gate-inbox")
  def statePartConsumerTopic: TopicName = TopicName("http-gate-state")
  def sseStatusTopic: TopicName = TopicName("sse-status")
  def httpPostTopic: TopicName = TopicName("http-posts")

  //def commandReceivers: List[Receiver[_]] = ???
  def protocols: List[Protocol] = QProtocol :: HttpProtocol :: Nil
  //def dataDependencies: List[DataDependencyTo[_]] = ???
  def worldProvider: WorldProvider = ???

  def consumerGroupId: String = ???


}

////

trait WorldProvider {
  def value: World
}

//











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
