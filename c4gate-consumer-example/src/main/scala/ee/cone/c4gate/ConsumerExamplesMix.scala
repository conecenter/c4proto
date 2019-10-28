package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.c4app



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
  with HttpProtocolApp

@c4app class TestConsumerAppBase extends TestServerApp
  with ManagementApp
  with AlienProtocolApp
  with TcpProtocolApp
  with ParallelObserversApp

@c4app class HiRateTxAppBase extends TestServerApp with ParallelObserversApp

trait TestTxTransformAppBase extends TestServerApp
@c4app class TestSerialApp extends TestTxTransformApp with SerialObserversApp
@c4app class TestParallelApp extends TestTxTransformApp with ParallelObserversApp
