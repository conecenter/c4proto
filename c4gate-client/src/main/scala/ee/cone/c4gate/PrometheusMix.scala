package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, GzipCompressorApp, ProtocolsApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait ActorAccessApp extends AssemblesApp with ProtocolsApp {
  override def assembles: List[Assemble] =
    new ActorAccessAssemble :: super.assembles
  override def protocols: List[Protocol] =
    ActorAccessProtocol :: super.protocols
}

trait PrometheusApp extends AssemblesApp with ProtocolsApp with GzipCompressorApp {
  override def assembles: List[Assemble] =
    new PrometheusAssemble(compressor) :: super.assembles
}

trait AvailabilityApp extends AssemblesApp {
  override def assembles: List[Assemble] =
    new AvailabilityAssemble :: super.assembles
}

