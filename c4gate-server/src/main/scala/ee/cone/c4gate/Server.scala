
package ee.cone.c4gate

import ee.cone.c4actor._

class HttpGatewayApp extends ServerApp
  with EnvConfigApp
  with InternetForwarderApp
  with HttpServerApp
  with SSEServerApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp

object HttpGateway extends Main((new HttpGatewayApp).execution.run)

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content