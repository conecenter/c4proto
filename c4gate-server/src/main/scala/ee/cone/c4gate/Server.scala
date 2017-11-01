
package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble._

class HttpGatewayApp extends ServerApp
  with `The EnvConfigImpl` with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with InternetForwarderApp
  with HttpServerApp
  with SSEServerApp
  with `The NoAssembleProfiler`
  with `The MortalFactoryImpl`
  with ManagementApp
  with FileRawSnapshotApp
{
  def httpHandlers: List[RHttpHandler] =
    pongHandler :: new HttpPostHandler(`the QMessages`,worldProvider) :: Nil
  def sseConfig: SSEConfig = NoProxySSEConfig(`the Config`.get("C4STATE_REFRESH_SECONDS").toInt)
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content