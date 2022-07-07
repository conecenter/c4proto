package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchApp
import ee.cone.c4actor_kafka_impl.{KafkaConsumerApp, KafkaProducerApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4actor_repl_impl.SSHDebugApp
import ee.cone.c4di.c4app
import ee.cone.c4gate.{AuthProtocolApp, AvailabilityApp, ManagementApp, MergingSnapshotApp, PublisherApp, PublishingCompApp, RemoteRawSnapshotApp, SessionAttrCompApp}

trait CanvasAppBase

@c4app class TestSSEAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with BranchApp
  with AlienExchangeCompApp
  with NoAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with BasicLoggingApp

trait TestTagsAppBase

trait ReactHtmlAppBase

trait TestTxLogAppBase

@c4app class TestCanvasAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UICompApp
  with TestTagsApp
  with CanvasApp
  with NoAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryCompApp
  with SessionAttrCompApp
  with MortalFactoryCompApp
  with BasicLoggingApp
  with ReactHtmlApp

@c4app class TestCoWorkAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UICompApp
  with TestTagsApp
  with SimpleAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryCompApp
  with SessionAttrCompApp
  with MortalFactoryCompApp
  with MergingSnapshotApp
  with TestTxLogApp
  with SSHDebugApp
  with BasicLoggingApp
  with ReactHtmlApp

@c4app class TestPasswordAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UICompApp
  with TestTagsApp
  with NoAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with BasicLoggingApp
  with AuthProtocolApp
  with ReactHtmlApp

@c4app class TestTodoAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UICompApp
  with TestTagsApp
  with NoAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryCompApp
  with SessionAttrCompApp
  with MortalFactoryCompApp
  with AvailabilityApp
  with BasicLoggingApp
  with ReactHtmlApp
  with PublisherApp
  with SkipWorldPartsApp
  with FrontApp
  with ScalingApp
