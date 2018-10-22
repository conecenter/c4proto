package ee.cone.c4gate

import ee.cone.c4actor._
import java.time.Duration

import ee.cone.c4proto.Protocol

trait SnapshotMakingApp extends ExecutableApp with ProtocolsApp {
  def snapshotMakingRawObserver: RawObserver
  //
  lazy val snapshotTargetsDir = "db4/snapshot_targets"
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val toUpdate: ToUpdate = new ToUpdateImpl(qAdapterRegistry)()
  lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(snapshotMakingRawObserver)
  override def protocols: List[Protocol] = QProtocol :: super.protocols
}

class SnapshotMakerApp extends SnapshotMakingApp
  with EnvConfigApp with VMExecutionApp
  with KafkaConsumerApp
  with KafkaProducerApp
  with FileRawSnapshotApp
{
  lazy val snapshotMakingRawObserver: RawObserver = new DoubleRawObserver(
    new PeriodicRawObserver(Duration.ofMinutes(60),
      new SnapshotMakingRawObserver()
    ),
    new PeriodicRawObserver(Duration.ofSeconds(2),
      new SnapshotMergingRawObserver(rawQSender,
        new SnapshotLoaderImpl(new FileRawSnapshotLoader(snapshotTargetsDir))
      )
    )
  )
  lazy val snapshotLoader: SnapshotLoader =
    new SnapshotLoaderImpl(rawSnapshotLoader)
  lazy val rawWorldFactory: RawWorldFactory =
    new SnapshotLoadingRawWorldFactory(None, snapshotLoader,
      new SnapshotMakingRawWorldFactory(snapshotConfig, toUpdate,
        new SnapshotSaverImpl(new FileRawSnapshotSaver(snapshotsDir))
      )
    )
}

class DebugSnapshotMakerApp extends SnapshotMakingApp
  with EnvConfigApp with VMExecutionApp
  with KafkaConsumerApp
{

  //C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.DebugSnapshotMakerApp",
  // prod C4BOOTSTRAP_SERVERS => $bootstrap_server, C4SNAPSHOTS_URL => "http://frpc:7980/snapshots"

  lazy val snapshotConfig: SnapshotConfig = NoSnapshotConfig
  private lazy val debugOffset = config.get("C4DEBUG_OFFSET")
  private lazy val debugSnapshotURL = config.get("C4SNAPSHOTS_URL")
  lazy val rawWorldFactory: RawWorldFactory =
    new DebugSavingRawWorldFactory(debugOffset, toUpdate, execution,
      new SnapshotLoadingRawWorldFactory(Option(debugOffset),
        new SnapshotLoaderImpl(new RemoteRawSnapshotLoader(debugSnapshotURL)),
        new SnapshotMakingRawWorldFactory(snapshotConfig,toUpdate,
          new SnapshotSaverImpl(new FileRawSnapshotSaver(snapshotTargetsDir))
        )
      )
    )
  lazy val snapshotMakingRawObserver: RawObserver = CompletedRawObserver
}

trait SafeToRunApp extends ToStartApp {
  def snapshotLoader: SnapshotLoader
  //
  override def toStart: List[Executable] =
    new SafeToRun(snapshotLoader) :: super.toStart
}