
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

trait DataDependenciesApp {
  def dataDependencies: List[DataDependencyTo[_]] = Nil
}

trait ToStartApp {
  def toStart: List[Executable] = Nil
}

trait InitialObserversApp {
  def initialObservers: List[Observer] = Nil
}

trait ProtocolsApp {
  def protocols: List[Protocol] = Nil
}

trait AssemblesApp {
  def assembles: List[Assemble] = Nil
}

trait ToInjectApp {
  def toInject: List[ToInject] = Nil
}

trait EnvConfigApp {
  lazy val config: Config = new EnvConfigImpl
}

trait UMLClientsApp {
  lazy val umlExpressionsDumper: ExpressionsDumper[String] = UMLExpressionsDumper
}

trait ExpressionsDumpersApp {
  def expressionsDumpers: List[ExpressionsDumper[Unit]] = Nil
}

trait SimpleIndexValueMergerFactoryApp //compat
trait TreeIndexValueMergerFactoryApp //compat

trait ServerApp extends RichDataApp with ExecutableApp with InitialObserversApp with ToStartApp with ProtocolsApp with DeCompressorsApp {
  def execution: Execution
  def snapshotMaker: SnapshotMaker
  def rawSnapshotLoader: RawSnapshotLoader
  def consuming: Consuming
  def txObserver: Option[Observer]
  def rawQSender: RawQSender
  //
  lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)
  lazy val qMessages: QMessages = new QMessagesImpl(toUpdate, ()⇒rawQSender)
  lazy val txTransforms: TxTransforms = new TxTransforms(qMessages)
  private lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(initialObservers, new CompletingRawObserver(execution))))
  private lazy val rootConsumer =
    new RootConsumer(richRawWorldFactory, richRawWorldReducer, snapshotMaker, snapshotLoader, progressObserverFactory, consuming)
  override def toStart: List[Executable] = rootConsumer :: super.toStart
  override def initialObservers: List[Observer] = txObserver.toList ::: super.initialObservers
  override def protocols: List[Protocol] = OrigMetaAttrProtocol :: super.protocols
  override def deCompressors: List[DeCompressor] = GzipFullCompressor() :: super.deCompressors
}

trait TestRichDataApp extends RichDataApp {
  lazy val contextFactory = new ContextFactory(richRawWorldFactory,richRawWorldReducer,toUpdate)
}

trait RichDataApp extends ProtocolsApp
  with AssemblesApp
  with DataDependenciesApp
  with ToInjectApp
  with DefaultModelFactoriesApp
  with ExpressionsDumpersApp
  with PreHashingApp
  with DeCompressorsApp
  with RawCompressorsApp
{
  def assembleProfiler: AssembleProfiler
  //
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val toUpdate: ToUpdate = new ToUpdateImpl(qAdapterRegistry, deCompressorRegistry, Single.option(rawCompressors), 50000000L)()
  lazy val byPriority: ByPriority = ByPriorityImpl
  lazy val preHashing: PreHashing = PreHashingImpl
  lazy val richRawWorldReducer: RichRawWorldReducer =
    new RichRawWorldReducerImpl
  lazy val richRawWorldFactory: RichRawWorldFactory =
    new RichRawWorldFactoryImpl(toInject,toUpdate,getClass.getName,richRawWorldReducer)
  lazy val defaultModelRegistry: DefaultModelRegistry = new DefaultModelRegistryImpl(defaultModelFactories)()
  lazy val modelConditionFactory: ModelConditionFactory[Unit] = new ModelConditionFactoryImpl[Unit]
  lazy val hashSearchFactory: HashSearch.Factory = new HashSearchImpl.FactoryImpl(modelConditionFactory, preHashing, idGenUtil)
  def assembleSeqOptimizer: AssembleSeqOptimizer = new NoAssembleSeqOptimizer //new ShortAssembleSeqOptimizer(backStageFactory,indexUpdater) //make abstract
  lazy val indexUpdater: IndexUpdater = new IndexUpdaterImpl
  lazy val backStageFactory: BackStageFactory = new BackStageFactoryImpl(indexUpdater,indexUtil)
  lazy val idGenUtil: IdGenUtil = IdGenUtilImpl()()
  lazy val indexUtil: IndexUtil = IndexUtilImpl()()
  private lazy val deCompressorRegistry: DeCompressorRegistry = DeCompressorRegistryImpl(deCompressors)()
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl(indexUtil,indexUpdater)
  private lazy val treeAssembler: TreeAssembler = new TreeAssemblerImpl(indexUtil,byPriority,expressionsDumpers,assembleSeqOptimizer,backStageFactory)
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  private lazy val localQAdapterRegistryInit = new LocalQAdapterRegistryInit(qAdapterRegistry)
  private lazy val origKeyFactory = OrigKeyFactory(indexUtil)
  private lazy val assemblerInit =
    new AssemblerInit(qAdapterRegistry, toUpdate, treeAssembler, ()⇒dataDependencies, parallelAssembleOn, indexUtil, origKeyFactory, assembleProfiler)
  def parallelAssembleOn: Boolean = false
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols.distinct,origKeyFactory)() ::: super.dataDependencies
  override def toInject: List[ToInject] =
    assemblerInit ::
    localQAdapterRegistryInit ::
    NextOffsetInit ::
    super.toInject
}

trait VMExecutionApp {
  def toStart: List[Executable]
  lazy val execution: Execution = new VMExecution(()⇒toStart)
}

trait FileRawSnapshotApp { // Remote!
  def config: Config
  def idGenUtil: IdGenUtil
  //
  private lazy val appURL: String = config.get("C4HTTP_SERVER")
  lazy val signer: Signer[List[String]] = new SimpleSigner(config.get("C4AUTH_KEY_FILE"), idGenUtil)()
  lazy val rawSnapshotLoader: RawSnapshotLoader = new RemoteRawSnapshotLoader(appURL)
  lazy val snapshotMaker: SnapshotMaker = new RemoteSnapshotMaker(appURL, remoteSnapshotUtil, snapshotTaskSigner)
  lazy val remoteSnapshotUtil: RemoteSnapshotUtil = new RemoteSnapshotUtilImpl
  lazy val snapshotTaskSigner: Signer[SnapshotTask] = new SnapshotTaskSigner(signer)()
}

trait MergingSnapshotApp {
  def toUpdate: ToUpdate
  def richRawWorldFactory: RichRawWorldFactory
  def richRawWorldReducer: RichRawWorldReducer
  def snapshotLoader: SnapshotLoader
  def snapshotMaker: SnapshotMaker
  def remoteSnapshotUtil: RemoteSnapshotUtil
  def snapshotTaskSigner: Signer[SnapshotTask]
  //
  lazy val snapshotMerger: SnapshotMerger = new SnapshotMergerImpl(
    toUpdate, snapshotMaker,snapshotLoader,
    remoteSnapshotUtil,RemoteRawSnapshotLoaderFactory,SnapshotLoaderFactoryImpl,
    richRawWorldFactory,richRawWorldReducer, snapshotTaskSigner
  )
}

trait SerialObserversApp {
  def txTransforms: TxTransforms
  lazy val txObserver = Option(new SerialObserver(Map.empty)(txTransforms))
}

trait ParallelObserversApp {
  def execution: Execution
  def txTransforms: TxTransforms
  lazy val txObserver = Option(new ParallelObserver(Map.empty,txTransforms,execution))
}

trait MortalFactoryApp extends AssemblesApp {
  def idGenUtil: IdGenUtil
  //
  def mortal: MortalFactory = MortalFactoryImpl(idGenUtil)
  override def assembles: List[Assemble] =
    new MortalFatalityAssemble() :: super.assembles
}

trait NoAssembleProfilerApp {
  lazy val assembleProfiler: AssembleProfiler = NoAssembleProfiler
}

trait SimpleAssembleProfilerApp extends ProtocolsApp {
  def idGenUtil: IdGenUtil
  def toUpdate: ToUpdate
  //
  lazy val assembleProfiler: AssembleProfiler = SimpleAssembleProfiler(idGenUtil)(toUpdate)
  //
  override def protocols: List[Protocol] =
    SimpleAssembleProfilerProtocol ::
    super.protocols
}