package ee.cone.c4gate

import ee.cone.c4actor._


class SnapshotMakerApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with UMLClientsApp
{
  def txObserver: Option[Observer] = None
}
