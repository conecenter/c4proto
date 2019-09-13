
package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

abstract class AbstractHttpGatewayApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with InternetForwarderApp
  with SSEServerApp
  with NoAssembleProfilerApp
  with MortalFactoryApp
  with ManagementApp
  with SnapshotMakingApp
  with SnapshotPutApp
  with LZ4RawCompressorApp
  with BasicLoggingApp
  with HttpHandlersApp
  with NoProxySSEConfigApp
  with SafeToRunApp
  with WorldProviderApp



// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content

trait InternetForwarderApp extends ProtocolsApp with InitialObserversApp with ToStartApp {
  def httpServer: Executable
  def config: Config
  //
  lazy val httpPort: Int = config.get("C4HTTP_PORT").toInt
  override def protocols: List[Protocol] = AuthProtocol :: HttpProtocol :: super.protocols
  override def toStart: List[Executable] = httpServer :: super.toStart
}

trait WorldProviderApp extends ToStartApp with InitialObserversApp {
  def qMessages: QMessages
  def execution: Execution
  def statefulReceiverFactory: StatefulReceiverFactory
  //
  lazy val worldProvider: WorldProvider with Observer[RichContext] with Executable =
    new WorldProviderImpl(qMessages, execution, statefulReceiverFactory)()
  override def initialObservers: List[Observer[RichContext]] = worldProvider :: super.initialObservers
  override def toStart: List[Executable] = worldProvider :: super.toStart
}

trait SafeToRunApp extends ToStartApp {
  def safeToRun: SafeToRun
  override def toStart: List[Executable] = safeToRun :: super.toStart
}

trait HttpHandlersApp {
  def pongRegistry: PongRegistry
  def snapshotLoader: SnapshotLoader
  def sseConfig: SSEConfig
  def worldProvider: WorldProvider
  //()//todo secure?
  lazy val httpResponseFactory: RHttpResponseFactory = RHttpResponseFactoryImpl
  def httpHandler: FHttpHandler = new FHttpHandlerImpl(worldProvider, httpResponseFactory,
    new HttpGetSnapshotHandler(snapshotLoader, httpResponseFactory,
      new GetPublicationHttpHandler(httpResponseFactory,
        new PongHandler(sseConfig, pongRegistry, httpResponseFactory,
          new NotFoundProtectionHttpHandler(httpResponseFactory,
            new SelfDosProtectionHttpHandler(httpResponseFactory, sseConfig,
              new AuthHttpHandler(
                new DefSyncHttpHandler()
              )
            )
          )
        )
      )
    )
  )
}

trait SnapshotMakingApp extends ToStartApp with AssemblesApp {
  def snapshotLoader: SnapshotLoader
  def consuming: Consuming
  def toUpdate: ToUpdate
  def config: Config
  def idGenUtil: IdGenUtil
  def actorName: String
  def catchNonFatal: CatchNonFatal
  //
  lazy val rawSnapshotLoader: RawSnapshotLoader = fileRawSnapshotLoader
  lazy val snapshotMaker: SnapshotMaker = fileSnapshotMaker
  lazy val safeToRun: SafeToRun = new SafeToRun(fileSnapshotMaker)
  lazy val dbDir = config.get("C4DATA_DIR")
  lazy val signer: Signer[List[String]] =
    new SimpleSigner(config.get("C4AUTH_KEY_FILE"), idGenUtil)()
  //
  private lazy val fileSnapshotMaker: SnapshotMakerImpl =
    new SnapshotMakerImpl(snapshotConfig, fileRawSnapshotLoader, snapshotLoader, fileRawSnapshotLoader, fullSnapshotSaver, txSnapshotSaver, consuming, toUpdate)
  private lazy val snapshotConfig: SnapshotConfig =
    new FileSnapshotConfigImpl(dbDir)()
  private lazy val fullSnapshotSaver: SnapshotSaver =
    new SnapshotSaverImpl("snapshots",new FileRawSnapshotSaver(dbDir))
  private lazy val txSnapshotSaver: SnapshotSaver =
    new SnapshotSaverImpl("snapshot_txs",new FileRawSnapshotSaver(dbDir))
  private lazy val fileRawSnapshotLoader: FileRawSnapshotLoader =
    new FileRawSnapshotLoader(dbDir,SnapshotUtilImpl)
  private lazy val snapshotTaskSigner: Signer[SnapshotTask] =
    new SnapshotTaskSigner(signer)()
  //
  override def assembles: List[Assemble] =
    new SnapshotMakingAssemble(actorName, fileSnapshotMaker, snapshotTaskSigner, new SignedReqUtilImpl(catchNonFatal)) ::
    new PurgerAssemble(new Purger(fileRawSnapshotLoader,dbDir)) ::
    super.assembles
}

trait SnapshotPutApp extends AssemblesApp {
  def signer: Signer[List[String]]
  def snapshotDiffer: SnapshotDiffer
  def catchNonFatal: CatchNonFatal
  //
  private lazy val snapshotPutter =
    new SnapshotPutter(SnapshotLoaderFactoryImpl, snapshotDiffer)
  //
  override def assembles: List[Assemble] =
    new SnapshotPutAssemble(snapshotPutter, signer, new SignedReqUtilImpl(catchNonFatal)) ::
    super.assembles
}
