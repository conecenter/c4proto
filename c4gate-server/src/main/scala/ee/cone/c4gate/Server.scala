
package ee.cone.c4gate

import ee.cone.c4actor._

class HttpGatewayApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with InternetForwarderApp
  with HttpServerApp
  with SSEServerApp
  with KafkaApp
{
  def bootstrapServers: String = Option(System.getenv("C4BOOTSTRAP_SERVERS")).get
  def httpPort: Int = Option(System.getenv("C4HTTP_PORT")).get.toInt
  def ssePort: Int = Option(System.getenv("C4SSE_PORT")).get.toInt

  def sseActorName: ActorName = ActorName("http-gate")
  lazy val worldProvider: WorldProvider with Executable =
    actorFactory.create(sseActorName, messageHandlers)
  override def toStart: List[Executable] = worldProvider :: super.toStart
  def setOffset(task: Object, offset: Long): AnyRef = {task;???}
}

object HttpGateway extends Main((new HttpGatewayApp).execution.run)

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content