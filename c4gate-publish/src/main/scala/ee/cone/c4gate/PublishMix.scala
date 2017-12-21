package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble._

class PublishApp extends ServerApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaProducerApp with KafkaConsumerApp
  with TreeIndexValueMergerFactoryApp
  with PublishingApp
  with `The NoAssembleProfiler`
  with FileRawSnapshotApp
  with `The DefaultPublishConfig`

trait PublishingApp extends `The HttpProtocol`
  with `The PublishInitialObserversProvider` with `The DefaultPublishDir`
  with `The GzipCompressor`





