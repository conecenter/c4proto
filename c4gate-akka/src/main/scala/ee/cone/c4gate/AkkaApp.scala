
package ee.cone.c4gate

import ee.cone.c4actor.{Executable, Execution, ToStartApp}

class AkkaGatewayApp extends AbstractHttpGatewayApp with AkkaServerApp with AkkaStatefulReceiverFactoryApp with AkkaMatApp

trait AkkaServerApp {
  def execution: Execution
  def akkaMat: AkkaMat
  def httpHandler: FHttpHandler
  def httpPort: Int
  lazy val httpServer: Executable =
    new AkkaHttpServer(httpPort, httpHandler, execution, akkaMat)
}

trait AkkaStatefulReceiverFactoryApp {
  def execution: Execution
  def akkaMat: AkkaMat
  lazy val statefulReceiverFactory: StatefulReceiverFactory =
    new AkkaStatefulReceiverFactory(execution,akkaMat)
}

trait AkkaMatApp extends ToStartApp {
  lazy val akkaMat: AkkaMat with Executable = new AkkaMatImpl
  override def toStart: List[Executable] = akkaMat :: super.toStart
}