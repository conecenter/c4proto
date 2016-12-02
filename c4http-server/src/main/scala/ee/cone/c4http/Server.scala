
package ee.cone.c4http

import ee.cone.c4proto._

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
  def consumerGroupId: String = "http-gate"
  def statePartConsumerStreamKey: StreamKey = StreamKey("http-gate-state","") //http-gate-inbox
  def sseStatusStreamKey: StreamKey = StreamKey("","sse-status")
  def httpPostStreamKey: StreamKey = StreamKey("","http-posts")
  def sseEventStreamKey: StreamKey = StreamKey("sse-events","sse-status")
  //def commandReceivers: List[Receiver[_]] = ???
  //def dataDependencies: List[DataDependencyTo[_]] = ???
}



object HttpGateway {
  def main(args: Array[String]): Unit = try {
    println("EEE")
    val app = new HttpGatewayApp
    val consumer = app.worldProvider
    consumer.start()
    println("DDD")
    while(consumer.state < ConsumerState.started) {
      println(consumer.state)
      Thread.sleep(1000)
    }
    println("B",consumer.state)
    app.toStart.foreach(_.start())
    while(consumer.state < ConsumerState.finished) {
      println(consumer.state)
      Thread.sleep(1000)
    }
    println("C",consumer.state)
    Thread.sleep(1000)
  } finally {} //System.exit(0)
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content