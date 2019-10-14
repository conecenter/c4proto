package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.c4app
import ee.cone.c4ui.{AccessViewApp, AlienExchangeApp, PublicViewAssembleApp, UIApp}

trait CanvasAppBase

@c4app class TestSSEAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with BranchApp
  with AlienExchangeApp
  with NoAssembleProfilerApp
  with ManagementApp
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
  with UIApp
  with PublishingCompApp
  with TestTagsApp
  with CanvasApp
  with NoAssembleProfilerApp
  with ManagementApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryApp
  with SessionAttrApp
  with MortalFactoryCompApp
  with BasicLoggingApp
  with ReactHtmlApp

@c4app class TestCoWorkAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with PublishingCompApp
  with TestTagsApp
  with SimpleAssembleProfilerApp
  with ManagementApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryApp
  with SessionAttrApp
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
  with UIApp
  with TestTagsApp
  with NoAssembleProfilerApp
  with ManagementApp
  with RemoteRawSnapshotApp
  with BasicLoggingApp
  with AuthProtocolApp
  with ReactHtmlApp

@c4app class TestTodoAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with NoAssembleProfilerApp
  with ManagementApp
  with RemoteRawSnapshotApp
  with PublicViewAssembleApp
  with CommonFilterApp
  with FilterPredicateBuilderApp
  with ModelAccessFactoryApp
  with AccessViewApp
  with SessionAttrApp
  with MortalFactoryCompApp
  with AvailabilityApp
  with BasicLoggingApp
  with ReactHtmlApp
