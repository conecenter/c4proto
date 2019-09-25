package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, Config, Executable, GzipStreamCompressorFactory, MortalFactory, ProtocolsApp, QMessages, StreamCompressorFactory, ToInject, ToInjectApp, ToStartApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait SSEServerApp
  extends ToStartApp
    with AssemblesApp
    with ToInjectApp
    with ProtocolsApp
{
  def config: Config
  def qMessages: QMessages
  def sseConfig: SSEConfig
  def mortal: MortalFactory
  lazy val pongRegistry: PongRegistry = new PongRegistry
  private lazy val ssePort = config.get("C4SSE_PORT").toInt
  private lazy val compressorFactory: StreamCompressorFactory = new GzipStreamCompressorFactory
  private lazy val sseServer =
    new TcpServerImpl(ssePort, new SSEHandler(sseConfig), 10, compressorFactory)
  override def toStart: List[Executable] = sseServer :: super.toStart
  override def assembles: List[Assemble] =
    SSEAssembles(mortal) ::: HttpReqAssembles(mortal,sseConfig) :::
      super.assembles
  override def toInject: List[ToInject] =
    sseServer :: pongRegistry :: super.toInject
  override def protocols: List[Protocol] = AlienProtocol :: super.protocols
}

trait NoProxySSEConfigApp {
  def config: Config
  def sseConfig: SSEConfig = NoProxySSEConfig(config.get("C4STATE_REFRESH_SECONDS").toInt)
}