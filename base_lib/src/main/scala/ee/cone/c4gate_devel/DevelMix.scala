package ee.cone.c4gate_devel

import ee.cone.c4actor._
import ee.cone.c4actor_kafka_impl.{DisableDefaultKafkaConsumingApp, KafkaConsumerApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4di.c4app
import ee.cone.c4gate.DisableDefaultRemoteRawSnapshotApp

@c4app class TopicToDirAppBase extends VMExecutionApp with ExecutableApp with BaseApp with ProtoApp
  with KafkaConsumerApp with SnapshotUtilImplApp with EnvConfigCompApp
  with BasicLoggingApp with CatchNonFatalApp

trait FileConsumerAppBase
trait WorldCheckerAppBase
trait ReplayApp extends NoObserversApp
  with DisableDefaultKafkaConsumingApp
  with DisableDefaultRemoteRawSnapshotApp
  with FileConsumerApp
  with WorldCheckerApp