package ee.cone.c4gate

import ee.cone.c4actor.{Executable, Execution}

class SunGatewayApp extends AbstractHttpGatewayApp with SunServerApp

trait SunServerApp {
  def execution: Execution
  def httpHandler: RHttpHandler
  def httpPort: Int
  lazy val httpServer: Executable =
    new SunHttpServer(httpPort, httpHandler, execution)
}
