package ee.cone.c4gate

import ee.cone.c4actor._
import java.time.Duration

class SnapshotMakerApp extends SnapshotMakingApp
  with EnvConfigApp with VMExecutionApp
  with KafkaConsumerApp
  with FileRawSnapshotApp
{
  lazy val snapshotMakingRawObserver: RawObserver =
    new PeriodicSnapshotMakingRawObserver(rawSnapshot,Duration.ofMinutes(60))
}


