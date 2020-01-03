
package ee.cone.c4gate_akka

import ee.cone.c4actor_repl_impl.SSHDebugApp
import ee.cone.c4di.c4app
import ee.cone.c4gate_server.AbstractHttpGatewayApp

trait AkkaMatAppBase
trait AkkaServerAppBase
trait AkkaStatefulReceiverFactoryAppBase
trait AkkaGatewayAppBase extends AbstractHttpGatewayApp with AkkaStatefulReceiverFactoryApp with AkkaMatApp

@c4app class SimpleAkkaGatewayAppBase extends AkkaGatewayApp with SSHDebugApp
