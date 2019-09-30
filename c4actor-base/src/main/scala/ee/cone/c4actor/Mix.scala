
package ee.cone.c4actor

import ee.cone.c4actor.ComponentRegistry.provide
import ee.cone.c4assemble._
import ee.cone.c4proto.{AbstractComponents, Component, ComponentsApp, c4component}

trait ToStartApp extends ComponentsApp {
  private lazy val executableComponent = ComponentRegistry.provide(classOf[Executable], Nil, ()=>toStart)
  override def components: List[Component] = executableComponent :: super.components
  def toStart: List[Executable] = Nil
}

trait InitialObserversApp {
  def initialObservers: List[Observer[RichContext]] = Nil
}

trait AssemblesApp extends ComponentsApp {
  private lazy val assembleComponent = ComponentRegistry.provide(classOf[Assemble], Nil, ()=>assembles)
  override def components: List[Component] = assembleComponent :: super.components
  def assembles: List[Assemble] = Nil
}

trait ToInjectApp extends ComponentsApp {
  private lazy val toInjectComponent = ComponentRegistry.provide(classOf[ToInject], Nil, ()=>toInject)
  override def components: List[Component] = toInjectComponent :: super.components
  def toInject: List[ToInject] = Nil
}

trait UpdatesProcessorsApp extends ComponentsApp {
  private lazy val processorsComponent = ComponentRegistry.provide(classOf[UpdatesPreprocessor], Nil, ()=>processors)
  override def components: List[Component] = processorsComponent :: super.components
  def processors: List[UpdatesPreprocessor] = Nil
}

trait EnvConfigApp extends EnvConfigAutoApp {
  def componentRegistry: ComponentRegistry
  lazy val config: Config = componentRegistry.resolveSingle(classOf[Config])
  lazy val actorName: String = componentRegistry.resolveSingle(classOf[ActorName]).value
}

trait UMLClientsApp {
  lazy val umlExpressionsDumper: ExpressionsDumper[String] = UMLExpressionsDumper
}

trait ExpressionsDumpersApp extends ComponentsApp {
  private lazy val expressionsDumpersComponent = ComponentRegistry.provide(classOf[ExpressionsDumper[Unit]], List(ComponentRegistry.toTypeKey(classOf[Unit],Nil)), ()=>expressionsDumpers)
  override def components: List[Component] = expressionsDumpersComponent :: super.components
  def expressionsDumpers: List[ExpressionsDumper[Unit]] = Nil
}

@deprecated trait SimpleIndexValueMergerFactoryApp
@deprecated trait TreeIndexValueMergerFactoryApp

trait ServerApp extends ServerAutoApp with RichDataApp with ExecutableApp with InitialObserversApp with ToStartApp with AssemblesApp {
  def execution: Execution
  def snapshotMaker: SnapshotMaker
  def rawSnapshotLoader: RawSnapshotLoader
  def consuming: Consuming
  def txObserver: Option[Observer[RichContext]]
  def rawQSender: RawQSender
  //
  def longTxWarnPeriod: Long = Option(System.getenv("C4TX_WARN_PERIOD_MS")).fold(500L)(_.toLong)
  lazy val snapshotLoader: SnapshotLoader = new SnapshotLoaderImpl(rawSnapshotLoader)
  lazy val qMessages: QMessages = new QMessagesImpl(toUpdate, ()=>rawQSender)
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
}

trait TestVMRichDataApp extends RichDataApp with VMExecutionApp with ToStartApp {
  lazy val contextFactory = new ContextFactory(richRawWorldReducer,toUpdate)
  lazy val actorName: String = getClass.getName
}

trait BaseApp extends BaseAutoApp with AbstractComponents {
  lazy val componentRegistry = ComponentRegistry(this)
}

trait ProtoApp extends ProtoAutoApp {
  def componentRegistry: ComponentRegistry
  //
  lazy val qAdapterRegistry: QAdapterRegistry =
    componentRegistry.resolveSingle(classOf[QAdapterRegistry])
  lazy val toUpdate: ToUpdate =
    componentRegistry.resolveSingle(classOf[ToUpdate])
}

@c4component("RichDataAutoApp") class DefUpdateCompressionMinSize extends UpdateCompressionMinSize(50000000L)
@c4component("RichDataAutoApp") class DefLongAssembleWarnPeriod extends LongAssembleWarnPeriod(Option(System.getenv("C4ASSEMBLE_WARN_PERIOD_MS")).fold(1000L)(_.toLong))
@c4component("RichDataAutoApp") class DefAssembleOptions extends AssembleOptions("AssembleOptions",false,0L)

trait DefaultKeyFactoryApp extends ComponentsApp {
  def origKeyFactoryOpt: Option[KeyFactory] = None
  private lazy val origKeyFactoryComponent =
    provide(classOf[OrigKeyFactoryHolder],Nil,()=>List(new OrigKeyFactoryHolder(origKeyFactoryOpt)))
  override def components: List[Component] = origKeyFactoryComponent :: super.components
}

trait DefaultUpdateProcessorApp extends ComponentsApp {
  def updateProcessor: UpdateProcessor = new DefaultUpdateProcessor
  private lazy val updateProcessorComponent =
    provide(classOf[UpdateProcessor],Nil,()=>List(updateProcessor))
  override def components: List[Component] = updateProcessorComponent :: super.components
}

trait AssembleProfilerApp extends ComponentsApp {
  def assembleProfiler: AssembleProfiler
  private lazy val assembleProfilerComponent =
    provide(classOf[AssembleProfiler],Nil,()=>List(assembleProfiler))
  override def components: List[Component] = assembleProfilerComponent :: super.components
}

trait RichDataApp extends RichDataAutoApp with BaseApp with ProtoApp with AssembleAutoApp with AssembleProfilerApp
  with DefaultKeyFactoryApp with DefaultUpdateProcessorApp
{
  import componentRegistry.resolveSingle
  lazy val preHashing: PreHashing = resolveSingle(classOf[PreHashing])
  lazy val richRawWorldReducer: RichRawWorldReducer = resolveSingle(classOf[RichRawWorldReducer])
  lazy val indexUtil: IndexUtil = resolveSingle(classOf[IndexUtil])
  lazy val idGenUtil: IdGenUtil = resolveSingle(classOf[IdGenUtil])
  lazy val defaultModelRegistry: DefaultModelRegistry = resolveSingle(classOf[DefaultModelRegistry])
  lazy val readModelUtil: ReadModelUtil = resolveSingle(classOf[ReadModelUtil])
  lazy val indexUpdater: IndexUpdater = resolveSingle(classOf[IndexUpdater])
  lazy val backStageFactory: BackStageFactory = resolveSingle(classOf[BackStageFactory])
  lazy val catchNonFatal: CatchNonFatal = resolveSingle(classOf[CatchNonFatal])
  lazy val modelConditionFactory: ModelConditionFactory[Unit] = resolveSingle(classOf[ModelConditionFactoryHolder]).value
  lazy val hashSearchFactory: HashSearch.Factory = resolveSingle(classOf[HashSearchFactoryHolder]).value
  @deprecated def parallelAssembleOn: Boolean = false
  @deprecated def assembleSeqOptimizer: AssembleSeqOptimizer = new NoAssembleSeqOptimizer
}

trait VMExecutionApp extends VMExecutionAutoApp {
  def componentRegistry: ComponentRegistry
  //
  lazy val execution: Execution = componentRegistry.resolveSingle(classOf[Execution])
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

trait MortalFactoryApp extends MortalFactoryAutoApp with AssemblesApp {
  def idGenUtil: IdGenUtil
  //
  def mortal: MortalFactory = MortalFactoryImpl(idGenUtil)
}

trait NoAssembleProfilerApp {
  lazy val assembleProfiler: AssembleProfiler = NoAssembleProfiler
}

trait SimpleAssembleProfilerApp extends SimpleAssembleProfilerAutoApp {
  def idGenUtil: IdGenUtil
  def toUpdate: ToUpdate
  //
  lazy val assembleProfiler: AssembleProfiler = SimpleAssembleProfiler(idGenUtil)(toUpdate)
}