package ee.cone.c4gate

import ee.cone.c4actor.{Executable, Execution}

class FinagleGatewayApp extends AbstractHttpGatewayApp with FinagleServerApp with MutableWorldProviderSenderApp

trait FinagleServerApp {
  def execution: Execution
  def httpHandler: FHttpHandler
  def httpPort: Int
  lazy val httpServer: Executable =
    new FinagleHttpServer(httpPort, httpHandler, execution)
}
