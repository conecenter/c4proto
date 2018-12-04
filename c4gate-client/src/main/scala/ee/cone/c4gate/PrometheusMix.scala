package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, GzipFullCompressor, ProtocolsApp}
import ee.cone.c4assemble.{Assemble, IndexUtil, ReadModelUtil}
import ee.cone.c4proto.Protocol

trait ActorAccessApp extends AssemblesApp with ProtocolsApp {
  override def assembles: List[Assemble] =
    new ActorAccessAssemble :: super.assembles
  override def protocols: List[Protocol] =
    ActorAccessProtocol :: super.protocols
}

trait PrometheusApp extends AssemblesApp with ProtocolsApp {
  def indexUtil: IndexUtil
  def readModelUtil: ReadModelUtil

  override def assembles: List[Assemble] =
    new PrometheusAssemble(GzipFullCompressor(), indexUtil, readModelUtil) :: super.assembles
}

trait AvailabilityApp extends AssemblesApp with ProtocolsApp {
  def availabilityDefaultUpdatePeriod: Long = 3000
  def availabilityDefaultTimeout: Long = 3000


  override def protocols: List[Protocol] = AvailabilitySettingProtocol :: super.protocols

  override def assembles: List[Assemble] =
    new AvailabilityAssemble(availabilityDefaultUpdatePeriod, availabilityDefaultTimeout) :: super.assembles
}

