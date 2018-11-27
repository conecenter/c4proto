
package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.purger.PurgerApp

class HttpGatewayApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with InternetForwarderApp
  with HttpServerApp
  with SSEServerApp
  with NoAssembleProfilerApp
  with MortalFactoryApp
  with ManagementApp
  with SnapshotMakingApp
  with LZ4CompressorApp
  with PurgerApp
{
  def httpHandlers: List[RHttpHandler] = //todo secure
    new HttpGetSnapshotHandler(snapshotLister, snapshotLoader, signer) ::
    new HttpGetPublicationHandler(worldProvider) ::
    pongHandler ::
    new HttpPostHandler(qMessages,worldProvider) ::
    Nil
  def sseConfig: SSEConfig = NoProxySSEConfig(config.get("C4STATE_REFRESH_SECONDS").toInt)
  override def toStart: List[Executable] = safeToRun :: super.toStart
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content

trait SnapshotMakingApp extends ToStartApp with AssemblesApp {
  def snapshotLister: SnapshotLister
  def snapshotLoader: SnapshotLoader
  def consuming: Consuming
  def toUpdate: ToUpdate
  def config: Config
  def idGenUtil: IdGenUtil
  //
  lazy val rawSnapshotLister: RawSnapshotLister = fileRawSnapshotLoader
  lazy val rawSnapshotLoader: RawSnapshotLoader = fileRawSnapshotLoader
  lazy val snapshotMaker: SnapshotMaker = fileSnapshotMaker
  lazy val safeToRun: SafeToRun = new SafeToRun(fileSnapshotMaker)
  lazy val dbDir = "db4"
  lazy val signer: Signer[List[String]] =
    new SimpleSigner(config.get("C4AUTH_KEY_FILE"), idGenUtil)()
  //
  private lazy val fileSnapshotMaker: SnapshotMakerImpl =
    new SnapshotMakerImpl(snapshotConfig, snapshotLister, snapshotLoader, fileRawSnapshotLoader, fullSnapshotSaver, txSnapshotSaver, consuming, toUpdate)
  private lazy val snapshotConfig: SnapshotConfig =
    new FileSnapshotConfigImpl(dbDir)()
  private lazy val fullSnapshotSaver: SnapshotSaver =
    new SnapshotSaverImpl("snapshots",new FileRawSnapshotSaver(dbDir))
  private lazy val txSnapshotSaver: SnapshotSaver =
    new SnapshotSaverImpl("snapshot_txs",new FileRawSnapshotSaver(dbDir))
  private lazy val fileRawSnapshotLoader: FileRawSnapshotLoader =
    new FileRawSnapshotLoader(dbDir)
  private lazy val snapshotTaskSigner: Signer[SnapshotTask] =
    new SnapshotTaskSigner(signer)()
  //
  override def assembles: List[Assemble] =
    new SnapshotMakingAssemble(getClass.getName,fileSnapshotMaker, snapshotTaskSigner) :: super.assembles
}
