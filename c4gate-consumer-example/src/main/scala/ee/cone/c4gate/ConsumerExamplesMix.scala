package ee.cone.c4gate

import ee.cone.c4actor._

class DumperAppBase extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerApp
  with ExecutableApp with RichDataCompApp
  with RemoteRawSnapshotApp
  with AlienProtocolApp
  with HttpProtocolApp
  with SnapshotLoaderImplApp

class KafkaLatTestAppBase extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerApp
  with ExecutableApp with RichDataCompApp
  with KafkaProducerApp with KafkaConsumerApp

trait TestServerAppBase extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerApp
  with ServerCompApp with BasicLoggingApp
  with KafkaProducerApp with KafkaConsumerApp
  with RemoteRawSnapshotApp
  with HttpProtocolApp

class TestConsumerAppBase extends TestServerApp
  with ManagementApp
  with AlienProtocolApp
  with TcpProtocolApp
  with ParallelObserversApp

class HiRateTxAppBase extends TestServerApp with ParallelObserversApp

abstract class TestTxTransformAppBase extends TestServerApp
class TestSerialApp extends TestTxTransformApp with SerialObserversApp
class TestParallelApp extends TestTxTransformApp with ParallelObserversApp
