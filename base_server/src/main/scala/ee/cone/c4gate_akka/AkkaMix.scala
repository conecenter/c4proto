
package ee.cone.c4gate_akka

//import ee.cone.c4actor_repl_impl.SSHDebugApp
import ee.cone.c4di.c4app
import ee.cone.c4gate_server.AbstractHttpGatewayApp
import ee.cone.c4gate.RoomsConfProtocolApp

trait AkkaMatAppBase
trait AkkaStatefulReceiverFactoryAppBase
trait AkkaGatewayAppBase extends AbstractHttpGatewayApp with AkkaMatApp with RoomsConfProtocolApp

//@c4app class
trait SimpleAkkaGatewayAppBase extends AkkaGatewayApp //with SSHDebugApp
