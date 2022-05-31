package ee.cone.c4gate_server

import ee.cone.c4actor._
import ee.cone.c4actor_kafka_impl._
import ee.cone.c4di.c4app

// C4MAX_REQUEST_SIZE=30000000 C4INBOX_TOPIC_PREFIX='' C4BOOTSTRAP_SERVERS=localhost:8092 C4STATE_TOPIC_PREFIX=ee.cone.c4gate.SimpleMakerApp sbt 'c4gate-server-example/run-main ee.cone.c4actor.ServerMain'

@c4app class SimpleMakerAppBase extends RichDataCompApp with ExecutableApp
  with EnvConfigCompApp with VMExecutionApp
  with SnapshotMakingApp with NoAssembleProfilerCompApp with KafkaConsumerApp with SnapshotLoaderImplApp
