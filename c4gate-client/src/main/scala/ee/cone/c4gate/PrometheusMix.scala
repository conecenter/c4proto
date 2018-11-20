package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, GzipCompressorApp, ProtocolsApp}
import ee.cone.c4assemble.{Assemble, IndexUtil, ReadModelUtil}
import ee.cone.c4proto.Protocol

trait ActorAccessApp extends AssemblesApp with ProtocolsApp {
  override def assembles: List[Assemble] =
    new ActorAccessAssemble :: super.assembles
  override def protocols: List[Protocol] =
    ActorAccessProtocol :: super.protocols
}

trait PrometheusApp extends AssemblesApp with ProtocolsApp with GzipCompressorApp {
  def indexUtil: IndexUtil
  def readModelUtil: ReadModelUtil
  override def assembles: List[Assemble] =
    new PrometheusAssemble(compressor, indexUtil, readModelUtil) :: super.assembles
}

trait AvailabilityApp extends AssemblesApp {
  override def assembles: List[Assemble] =
    new AvailabilityAssemble :: super.assembles
}

