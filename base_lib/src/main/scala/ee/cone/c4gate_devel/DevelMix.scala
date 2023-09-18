package ee.cone.c4gate_devel

import ee.cone.c4actor.{BaseApp, EnvConfigCompApp, ExecutableApp, ProtoApp, SnapshotUtilImplApp, VMExecutionApp}
import ee.cone.c4actor_kafka_impl.KafkaConsumerApp
import ee.cone.c4di.c4app

@c4app class TopicToDirAppBase extends VMExecutionApp with ExecutableApp with BaseApp with ProtoApp
  with KafkaConsumerApp with SnapshotUtilImplApp with EnvConfigCompApp