package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor_kafka_impl._
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4actor_repl_impl.SSHDebugApp
import ee.cone.c4di.c4app
import ee.cone.c4gate._

trait PublicViewAssembleAppBase

// TestTxLogApp MergingSnapshotApp SSHDebugApp
@c4app class TestTodoAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UICompApp
  with NoAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryCompApp
  with SessionAttrCompApp
  with MortalFactoryCompApp
  with AvailabilityApp
  with BasicLoggingApp
  with PublisherApp
  with SkipWorldPartsApp
  with LZ4RawCompressorApp
  with AuthProtocolApp
  with AuthOperationsApp
  with AkkaGatewayApp
  //with AlienProtocolApp