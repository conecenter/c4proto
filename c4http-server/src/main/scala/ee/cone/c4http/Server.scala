
package ee.cone.c4http

import ee.cone.c4proto._

class HttpGatewayApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with HttpServerApp
  with SSEServerApp
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
    val app = new HttpGatewayApp
    app.execution.run()
  } finally System.exit(0)
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content