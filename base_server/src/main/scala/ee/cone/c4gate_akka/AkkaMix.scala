
package ee.cone.c4gate_akka

import ee.cone.c4actor_repl_impl.SSHDebugApp
import ee.cone.c4di.c4app
import ee.cone.c4gate_server.AbstractHttpGatewayApp

trait AkkaMatAppBase
trait AkkaServerAppBase
trait AkkaStatefulReceiverFactoryAppBase
@c4app class AkkaGatewayAppBase extends AbstractHttpGatewayApp with AkkaStatefulReceiverFactoryApp with AkkaMatApp with SSHDebugApp
