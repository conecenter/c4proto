
package ee.cone.c4gate

import ee.cone.c4actor.Executable

class AkkaGatewayApp extends AbstractHttpGatewayApp with AkkaServerApp

trait AkkaServerApp {
  def httpHandler: RHttpHandler
  def httpPort: Int
  lazy val httpServer: Executable = new AkkaHttpServer(httpPort, httpHandler)
}
