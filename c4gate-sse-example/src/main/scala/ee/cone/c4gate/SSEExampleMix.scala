package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.c4app
import ee.cone.c4ui.{AccessViewApp, AlienExchangeApp, PublicViewAssembleApp, UICompApp}

trait CanvasAppBase

@c4app class TestSSEAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with BranchApp
  with AlienExchangeApp
  with NoAssembleProfilerCompApp
  with ManagementApp with PublishingCompApp
  with RemoteRawSnapshotApp
  with BasicLoggingApp

trait CommonFilterAppBase

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
  with CommonFilterApp
  with FilterPredicateBuilderApp
  with ModelAccessFactoryCompApp
  with AccessViewApp
  with SessionAttrCompApp
  with MortalFactoryCompApp
  with AvailabilityApp
  with BasicLoggingApp
  with ReactHtmlApp
