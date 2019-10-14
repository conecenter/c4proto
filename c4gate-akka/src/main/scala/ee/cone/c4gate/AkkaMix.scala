
package ee.cone.c4gate

import ee.cone.c4proto.c4app

trait AkkaMatAppBase
trait AkkaServerAppBase
trait AkkaStatefulReceiverFactoryAppBase
@c4app class AkkaGatewayAppBase extends AbstractHttpGatewayApp with AkkaStatefulReceiverFactoryApp with AkkaMatApp with SSHDebugApp
