
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4proto.{AbstractComponents, Component, Protocol}

import scala.collection.immutable

trait DataDependenciesApp {
  def dataDependencies: List[DataDependencyTo[_]] = Nil
}

trait ToStartApp {
  def toStart: List[Executable] = Nil
}

trait InitialObserversApp {
  def initialObservers: List[Observer[RichContext]] = Nil
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

trait ComponentsApp {
  def components: List[Component] = Nil
}

trait EnvConfigApp {
  lazy val config: Config = new EnvConfigImpl
  lazy val actorName: String = config.get("C4STATE_TOPIC_PREFIX")
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
  def txObserver: Option[Observer[RichContext]]
  def rawQSender: RawQSender
  //
  def longTxWarnPeriod: Long = Option(System.getenv("C4TX_WARN_PERIOD_MS")).fold(500L)(_.toLong)
  lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)
  lazy val qMessages: QMessages = new QMessagesImpl(toUpdate, ()⇒rawQSender)
  lazy val txTransforms: TxTransforms = new TxTransforms(qMessages, longTxWarnPeriod, catchNonFatal)
  private lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(initialObservers, new CompletingRawObserver(execution))))
  private lazy val rootConsumer =
    new RootConsumer(richRawWorldReducer, snapshotMaker, snapshotLoader, progressObserverFactory, consuming)
  lazy val snapshotDiffer: SnapshotDiffer = new SnapshotDifferImpl(
    toUpdate, richRawWorldReducer, snapshotMaker, snapshotLoader
  )
  override def toStart: List[Executable] = rootConsumer :: super.toStart
  override def initialObservers: List[Observer[RichContext]] = txObserver.toList ::: super.initialObservers
  override def protocols: List[Protocol] = MetaAttrProtocol :: super.protocols
  override def deCompressors: List[DeCompressor] = GzipFullCompressor() :: super.deCompressors
}

trait TestVMRichDataApp extends RichDataApp with VMExecutionApp with ToStartApp {
  lazy val contextFactory = new ContextFactory(richRawWorldReducer,toUpdate)
  lazy val actorName: String = getClass.getName
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
  with DefaultUpdateProcessorApp
  with UpdatesProcessorsApp
  with DefaultKeyFactoryApp
  with AbstractComponents
  with ComponentsApp
{
  def assembleProfiler: AssembleProfiler
  def actorName: String
  def execution: Execution
  //
  lazy val qAdapterRegistry: QAdapterRegistry = Single(componentRegistry.resolve(classOf[QAdapterRegistry],Nil))
  lazy val toUpdate: ToUpdate = new ToUpdateImpl(qAdapterRegistry, deCompressorRegistry, Single.option(rawCompressors), 50000000L)()()
  lazy val byPriority: ByPriority = ByPriorityImpl
  lazy val preHashing: PreHashing = PreHashingImpl
  lazy val richRawWorldReducer: RichRawWorldReducer =
    new RichRawWorldReducerImpl(toInject,toUpdate,actorName,execution)
  lazy val defaultModelRegistry: DefaultModelRegistry = new DefaultModelRegistryImpl(defaultModelFactories)()
  lazy val modelConditionFactory: ModelConditionFactory[Unit] = new ModelConditionFactoryImpl[Unit]
  lazy val hashSearchFactory: HashSearch.Factory = new HashSearchImpl.FactoryImpl(modelConditionFactory, preHashing, idGenUtil)
  def assembleSeqOptimizer: AssembleSeqOptimizer = new NoAssembleSeqOptimizer //new ShortAssembleSeqOptimizer(backStageFactory,indexUpdater) //make abstract
  lazy val readModelUtil: ReadModelUtil = new ReadModelUtilImpl(indexUtil)
  lazy val indexUpdater: IndexUpdater = new IndexUpdaterImpl(readModelUtil)
  lazy val backStageFactory: BackStageFactory = new BackStageFactoryImpl(indexUpdater,indexUtil)
  lazy val idGenUtil: IdGenUtil = IdGenUtilImpl()()
  lazy val indexUtil: IndexUtil = IndexUtilImpl()()
  lazy val catchNonFatal: CatchNonFatal = CatchNonFatalImpl
  private lazy val deCompressorRegistry: DeCompressorRegistry = DeCompressorRegistryImpl(deCompressors)()
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl(indexUtil,indexUpdater)
  private lazy val treeAssembler: TreeAssembler = new TreeAssemblerImpl(indexUtil,readModelUtil,byPriority,expressionsDumpers,assembleSeqOptimizer,backStageFactory)
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  private lazy val localQAdapterRegistryInit: ToInject = new LocalQAdapterRegistryInit(qAdapterRegistry)
  private lazy val origKeyFactory: KeyFactory = origKeyFactoryOpt.getOrElse(byPKKeyFactory)
  private lazy val assemblerInit: ToInject =
    new AssemblerInit(qAdapterRegistry, toUpdate, treeAssembler, ()⇒dataDependencies, indexUtil, byPKKeyFactory, origKeyFactory, assembleProfiler, readModelUtil, actorName, updateProcessor, processors, defaultAssembleOptions, longAssembleWarnPeriod, catchNonFatal)()
  private def longAssembleWarnPeriod: Long = Option(System.getenv("C4ASSEMBLE_WARN_PERIOD_MS")).fold(1000L)(_.toLong)
  private lazy val defaultAssembleOptions = AssembleOptions("AssembleOptions",parallelAssembleOn,0L)
  def parallelAssembleOn: Boolean = false
  private lazy val componentRegistry = ComponentRegistry(this)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(qAdapterRegistry,origKeyFactory)() ::: super.dataDependencies
  override def toInject: List[ToInject] =
    assemblerInit ::
      localQAdapterRegistryInit ::
      super.toInject
  def srcIdProtoAdapterHolderComponent: Component = SrcIdProtoAdapterHolderComponent
  override def components: List[Component] =
    List(
      ComponentRegistryImplComponent, QAdapterRegistryImplComponent,
      SeqComponentFactoryComponent, ArgAdapterComponentFactoryComponent,
      ListArgAdapterFactoryComponent, LazyListArgAdapterFactoryComponent, OptionArgAdapterFactoryComponent, LazyOptionArgAdapterFactoryComponent,
      BooleanDefaultArgumentComponent,    IntDefaultArgumentComponent,    LongDefaultArgumentComponent,    ByteStringDefaultArgumentComponent,    OKIOByteStringDefaultArgumentComponent,     StringDefaultArgumentComponent,
      BooleanProtoAdapterHolderComponent, IntProtoAdapterHolderComponent, LongProtoAdapterHolderComponent, ByteStringProtoAdapterHolderComponent, OKIOByteStringProtoAdapterHolderComponent,  StringProtoAdapterHolderComponent,
      SrcIdDefaultArgumentComponent, srcIdProtoAdapterHolderComponent
    ) :::
    protocols.distinct.flatMap(_.components) :::
    super.components
}

trait VMExecutionApp {
  def toStart: List[Executable]
  lazy val execution: Execution = new VMExecution(()⇒toStart)()()
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
  def richRawWorldReducer: RichRawWorldReducer
  def snapshotDiffer: SnapshotDiffer
  def remoteSnapshotUtil: RemoteSnapshotUtil
  def snapshotTaskSigner: Signer[SnapshotTask]
  //
  lazy val snapshotMerger: SnapshotMerger = new SnapshotMergerImpl(
    toUpdate, snapshotDiffer,
    remoteSnapshotUtil,RemoteRawSnapshotLoaderFactory,SnapshotLoaderFactoryImpl,
    richRawWorldReducer, snapshotTaskSigner
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