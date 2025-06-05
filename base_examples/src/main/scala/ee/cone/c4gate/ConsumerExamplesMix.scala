package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4actor_kafka_impl.{KafkaConsumerApp, KafkaProducerApp, LZ4DeCompressorApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4di.c4app
import ee.cone.c4actor_xml.S3ListerApp

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
  with ParallelObserversApp

@c4app class HiRateTxAppBase extends TestServerApp with ParallelObserversApp

trait TestTxTransformAppBase extends TestServerApp
@c4app class TestParallelApp extends TestTxTransformApp with ParallelObserversApp

trait LongHungryAppBase

@c4app class TestParallelExAppBase extends TestServerApp with ParallelObserversApp