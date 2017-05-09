
package ee.cone.c4gate

import ee.cone.c4actor._

class HttpGatewayApp extends ServerApp
  with EnvConfigApp
  with InternetForwarderApp
  with HttpServerApp
  with SSEServerApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
{
  def httpHandlers: List[RHttpHandler] =
    pongHandler :: new HttpPostHandler(qMessages,worldProvider) :: Nil
  def sseConfig: SSEConfig = NoProxySSEConfig(config.get("C4STATE_REFRESH_SECONDS").toInt)
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content