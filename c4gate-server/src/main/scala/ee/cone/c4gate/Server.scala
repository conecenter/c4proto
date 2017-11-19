
package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble._

class HttpGatewayApp extends ServerApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaProducerApp with KafkaConsumerApp
  with `The ParallelObserverProvider` with TreeIndexValueMergerFactoryApp
  with InternetForwarderApp
  with `The DefaultHttpServer`
  with SSEServerApp
  with `The NoAssembleProfiler`
  with ManagementApp
  with FileRawSnapshotApp
  with `The NoProxySSEConfig`


// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content