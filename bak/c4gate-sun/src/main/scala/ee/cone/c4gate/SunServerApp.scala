package ee.cone.c4gate

import ee.cone.c4actor.{Executable, Execution}

class SunGatewayApp extends AbstractHttpGatewayApp with SunServerApp with MutableStatefulReceiverFactoryApp

trait SunServerApp {
  def execution: Execution
  def httpHandler: FHttpHandler
  def httpPort: Int
  lazy val httpServer: Executable =
    new SunHttpServer(httpPort, httpHandler, execution)
}

trait MutableStatefulReceiverFactoryApp {
  def execution: Execution
  lazy val statefulReceiverFactory: StatefulReceiverFactory =
    new MutableStatefulReceiverFactory(execution)
}