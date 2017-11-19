package ee.cone.c4gate

import ee.cone.c4actor._
import java.time.Duration

class SnapshotMakerApp extends SnapshotMakingApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaConsumerApp
  with FileRawSnapshotApp
  with `The HourlySnapshotMakingRawObserverTreeFactory`



