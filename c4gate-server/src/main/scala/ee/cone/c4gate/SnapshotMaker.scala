package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

class SnapshotMakerApp extends SnapshotMakingApp
  with EnvConfigApp with VMExecutionApp
  with KafkaConsumerApp
  with FileRawSnapshotApp


