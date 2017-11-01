
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

trait ToStartApp {
  def toStart: List[Executable] = Nil
}

trait InitialObserversApp {
  def initialObservers: List[Observer] = Nil
}

trait ProtocolsApp {
  def protocols: List[Protocol] = Nil
}

trait AssemblesApp extends `The Assemble` {
  def assembles: List[Assemble] = `the List of Assemble`
}

trait ToInjectApp extends `The ToInject` {
  def toInject: List[ToInject] = `the List of ToInject`
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
  with ToInjectApp
  with DefaultModelFactoriesApp
  with ExpressionsDumpersApp
  with `The DataDependencyTo`
  with `The ByUKGetterFactoryImpl`
  with `The JoinKeyFactoryImpl`
  with `The ModelConditionFactoryImpl`
  with `The HashSearchFactoryImpl`
{
  def assembleProfiler: AssembleProfiler
  def indexValueMergerFactory: IndexValueMergerFactory
  //
  def `the QAdapterRegistry` = qAdapterRegistry
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val toUpdate: ToUpdate = new ToUpdateImpl(qAdapterRegistry)
  lazy val byPriority: ByPriority = ByPriorityImpl
  lazy val preHashing: PreHashing = PreHashingImpl
  lazy val rawWorldFactory: RawWorldFactory = new RichRawWorldFactory(contextFactory,toUpdate,getClass.getName)
  lazy val contextFactory = new ContextFactory(toInject)
  lazy val `the DefaultModelRegistry`: DefaultModelRegistry = new DefaultModelRegistryImpl(defaultModelFactories)()
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl(indexValueMergerFactory,assembleProfiler)
  private lazy val treeAssembler: TreeAssembler = new TreeAssemblerImpl(byPriority,expressionsDumpers)
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  private lazy val localQAdapterRegistryInit = new LocalQAdapterRegistryInit(qAdapterRegistry)
  private lazy val assemblerInit =
    new AssemblerInit(qAdapterRegistry, toUpdate, treeAssembler, ()⇒`the List of DataDependencyTo`)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def `the List of DataDependencyTo`: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols.distinct) ::: super.`the List of DataDependencyTo`
  override def toInject: List[ToInject] =
    assemblerInit ::
    localQAdapterRegistryInit ::
    super.toInject
}

trait SnapshotMakingApp extends ExecutableApp with ProtocolsApp {
  def execution: Execution
  def rawSnapshot: RawSnapshot
  def snapshotMakingRawObserver: RawObserver //new SnapshotMakingRawObserver(rawSnapshot, new CompletingRawObserver(execution))
  //
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val rawWorldFactory: RawWorldFactory = new SnapshotMakingRawWorldFactory(qAdapterRegistry)
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
  def mortal: MortalFactory = MortalFactoryImpl
}

trait NoAssembleProfilerApp {
  lazy val assembleProfiler = NoAssembleProfiler
}

trait SimpleAssembleProfilerApp {
  lazy val assembleProfiler = SimpleAssembleProfiler
}
