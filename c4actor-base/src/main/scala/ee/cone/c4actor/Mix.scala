
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

trait SimpleIndexValueMergerFactoryApp {
  def indexValueMergerFactory: IndexValueMergerFactory = new SimpleIndexValueMergerFactory
}

trait TreeIndexValueMergerFactoryApp {
  def indexValueMergerFactory: IndexValueMergerFactory =
    new TreeIndexValueMergerFactory(16)
}

trait ServerApp extends RichDataApp with RichObserverApp

trait RichObserverApp extends ExecutableApp with InitialObserversApp {
  def execution: Execution
  def rawQSender: RawQSender
  def txObserver: Option[Observer]
  def qAdapterRegistry: QAdapterRegistry
  //
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val txTransforms: TxTransforms = new TxTransforms(qMessages)
  lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(initialObservers, new CompletingRawObserver(execution))))
  override def initialObservers: List[Observer] = txObserver.toList ::: super.initialObservers
}

trait RichDataApp extends ProtocolsApp
  with AssemblesApp
  with DataDependenciesApp
  with ToInjectApp
  with DefaultModelFactoriesApp
  with ExpressionsDumpersApp
{
  def assembleProfiler: AssembleProfiler
  def indexValueMergerFactory: IndexValueMergerFactory
  //
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val toUpdate: ToUpdate = new ToUpdateImpl(qAdapterRegistry)
  lazy val byPriority: ByPriority = ByPriorityImpl
  lazy val preHashing: PreHashing = PreHashingImpl
  lazy val rawWorldFactory: RawWorldFactory = new RichRawWorldFactory(contextFactory,toUpdate,getClass.getName)
  lazy val contextFactory = new ContextFactory(toInject)
  lazy val defaultModelRegistry: DefaultModelRegistry = new DefaultModelRegistryImpl(defaultModelFactories)()
  lazy val modelConditionFactory: ModelConditionFactory[Unit] = new ModelConditionFactoryImpl[Unit]
  lazy val hashSearchFactory: HashSearch.Factory = new HashSearchImpl.FactoryImpl(modelConditionFactory, preHashing, uuidUtil)
  def assembleSeqOptimizer: AssembleSeqOptimizer = new NoAssembleSeqOptimizer //new ShortAssembleSeqOptimizer(backStageFactory,indexUpdater) //make abstract
  lazy val indexUpdater: IndexUpdater = new IndexUpdaterImpl
  lazy val backStageFactory: BackStageFactory = new BackStageFactoryImpl(indexUpdater)
  lazy val uuidUtil: UUIDUtil = UUIDUtilImpl()
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl(indexValueMergerFactory,assembleProfiler,indexUpdater)
  private lazy val treeAssembler: TreeAssembler = new TreeAssemblerImpl(byPriority,expressionsDumpers,assembleSeqOptimizer,backStageFactory)
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  private lazy val localQAdapterRegistryInit = new LocalQAdapterRegistryInit(qAdapterRegistry)
  private lazy val assemblerInit =
    new AssemblerInit(qAdapterRegistry, toUpdate, treeAssembler, ()⇒dataDependencies, parallelAssembleOn)
  def parallelAssembleOn: Boolean = false
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols.distinct) ::: super.dataDependencies
  override def toInject: List[ToInject] =
    assemblerInit ::
    localQAdapterRegistryInit ::
    super.toInject
}

trait SnapshotMakingApp extends ExecutableApp with ProtocolsApp {
  def execution: Execution
  def rawSnapshot: RawSnapshot
  def snapshotMakingRawObserver: RawObserver //new SnapshotMakingRawObserver(rawSnapshot, new CompletingRawObserver(execution))
  def snapshotConfig: SnapshotConfig
  //
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val rawWorldFactory: RawWorldFactory = new SnapshotMakingRawWorldFactory(qAdapterRegistry,snapshotConfig)
  lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(snapshotMakingRawObserver)
  override def protocols: List[Protocol] = QProtocol :: super.protocols
}

trait VMExecutionApp {
  def toStart: List[Executable]
  lazy val execution: Execution = new VMExecution(()⇒toStart)
}

trait FileRawSnapshotApp {
  def rawWorldFactory: RawWorldFactory
  lazy val rawSnapshot: RawSnapshot = new FileRawSnapshotImpl("db4/snapshots", rawWorldFactory)
  lazy val snapshotConfig: SnapshotConfig = new FileSnapshotConfigImpl("db4/snapshots")()
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
  def uuidUtil: UUIDUtil
  //
  def mortal: MortalFactory = MortalFactoryImpl(uuidUtil)
}

trait NoAssembleProfilerApp {
  lazy val assembleProfiler = NoAssembleProfiler
}

trait SimpleAssembleProfilerApp {
  lazy val assembleProfiler = SimpleAssembleProfiler
}
