package ee.cone.c4gate

import ee.cone.c4actor.{BaseApp, EnvConfigCompApp, ExecutableApp, KafkaConsumerApp, KafkaProducerApp, NoAssembleProfilerApp, RichDataCompApp, SnapshotLoaderImplApp, VMExecutionApp}
import ee.cone.c4proto.c4app

// C4MAX_REQUEST_SIZE=30000000 C4INBOX_TOPIC_PREFIX='' C4BOOTSTRAP_SERVERS=localhost:8092 C4STATE_TOPIC_PREFIX=ee.cone.c4gate.SimpleMakerApp sbt 'c4gate-server-example/run-main ee.cone.c4actor.ServerMain'

@c4app class SimpleMakerAppBase extends RichDataCompApp with ExecutableApp
  with EnvConfigCompApp with VMExecutionApp
  with SnapshotMakingApp with NoAssembleProfilerApp with KafkaConsumerApp with SnapshotLoaderImplApp

@c4app class SimplePusherAppBase extends BaseApp with ExecutableApp with EnvConfigCompApp
  with VMExecutionApp with NoAssembleProfilerApp with KafkaProducerApp with SnapshotLoaderImplApp with FileRawSnapshotLoaderApp with ConfigDataDirApp
