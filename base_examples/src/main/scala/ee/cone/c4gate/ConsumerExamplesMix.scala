package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4actor_kafka_impl.{KafkaConsumerApp, KafkaProducerApp, LZ4DeCompressorApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4di.c4app
import ee.cone.c4actor_xml.S3ListerApp

@c4app class DumperAppBase extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerCompApp
  with ExecutableApp with RichDataCompApp
  with RemoteRawSnapshotApp
  with AlienProtocolApp
  with HttpProtocolApp
  with SnapshotLoaderImplApp
  with LZ4DeCompressorApp

@c4app class KafkaLatTestAppBase extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerCompApp
  with ExecutableApp with RichDataCompApp
  with KafkaProducerApp with KafkaConsumerApp

trait TestServerApp extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerCompApp
  with ServerCompApp with BasicLoggingApp
  with KafkaProducerApp with KafkaConsumerApp
  with RemoteRawSnapshotApp
  with PublisherApp

@c4app class TestConsumerAppBase extends TestServerApp
  with ManagementApp
  with AlienProtocolApp
  with TcpProtocolApp
  with ParallelObserversApp

@c4app class HiRateTxAppBase extends TestServerApp with ParallelObserversApp

trait TestTxTransformAppBase extends TestServerApp
@c4app class TestSerialApp extends TestTxTransformApp with SerialObserversApp
@c4app class TestParallelApp extends TestTxTransformApp with ParallelObserversApp

@c4app class SimplePusherAppBase extends BaseApp with ExecutableApp with EnvConfigCompApp
  with VMExecutionApp with NoAssembleProfilerCompApp with KafkaProducerApp
  with SnapshotLoaderImplApp with S3RawSnapshotLoaderApp with S3ManagerApp with S3ListerApp
  with SnapshotUtilImplApp with SnapshotListProtocolApp
