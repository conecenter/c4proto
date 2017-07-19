package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

class SnapshotMakerApp extends ExecutableApp
  with ProtocolsApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with FileRawSnapshotApp
{
  lazy val execution: Executable = new ExecutionImpl(toStart)
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val rawObserver = new SnapshotMakingRawObserver(qAdapterRegistry,rawSnapshot)
  override def protocols: List[Protocol] = QProtocol :: super.protocols
}
