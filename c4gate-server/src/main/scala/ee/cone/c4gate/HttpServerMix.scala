package ee.cone.c4gate

import ee.cone.c4actor.{Config, Executable, Execution, InitialObserversApp, Observer, ProtocolsApp, ToStartApp}
import ee.cone.c4proto.Protocol

trait InternetForwarderApp extends ProtocolsApp with InitialObserversApp with ToStartApp {
  def httpServer: Executable
  def config: Config
  //
  lazy val httpPort = config.get("C4HTTP_PORT").toInt
  lazy val worldProvider: WorldProvider with Observer = new WorldProviderImpl()
  override def protocols: List[Protocol] = AuthProtocol :: HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] = worldProvider :: super.initialObservers
  override def toStart: List[Executable] = httpServer :: super.toStart
}


