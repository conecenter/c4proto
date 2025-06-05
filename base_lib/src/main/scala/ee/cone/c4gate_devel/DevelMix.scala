package ee.cone.c4gate_devel

import ee.cone.c4actor._
import ee.cone.c4actor_kafka_impl.{KafkaConsumerApp, LZ4DeCompressorApp, LZ4RawCompressorApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4actor_xml.S3ListerApp
import ee.cone.c4di.c4app
import ee.cone.c4gate.DevConfigApp
import ee.cone.c4gate.DisableDefaultSafeToRunApp

//@c4app class TopicToDirAppBase extends VMExecutionApp with ExecutableApp with BaseApp with ProtoApp
//  with KafkaConsumerApp with SnapshotUtilImplApp with EnvConfigCompApp
//  with BasicLoggingApp with CatchNonFatalApp with SnapshotSaverApp

@c4app class TopicToS3AppBase extends VMExecutionApp with ExecutableApp with BaseApp with ProtoApp
  with KafkaConsumerApp with SnapshotUtilImplApp with EnvConfigCompApp
  with BasicLoggingApp with CatchNonFatalApp with S3ListerApp with TxGroupApp

trait FileConsumerAppBase
trait WorldCheckerAppBase
trait TxGroupAppBase
trait ExtractTxAppBase extends TxGroupApp
trait ReplayApp extends FileConsumerApp with WorldCheckerApp with DisableDefaultS3RawSnapshotApp
  with ExtractTxApp with SnapshotUtilImplApp with DisableDefaultSafeToRunApp

@c4app class OrigStatReplayAppBase extends VMExecutionApp with ExecutableApp with BaseApp with ProtoApp
  with FileConsumerApp with SnapshotUtilImplApp with EnvConfigCompApp with DevConfigApp
  with ExtractTxApp with SnapshotLoaderImplApp with LZ4RawCompressorApp with LZ4DeCompressorApp
  with BasicLoggingApp with CatchNonFatalApp