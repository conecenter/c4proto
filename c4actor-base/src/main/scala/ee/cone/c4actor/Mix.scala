
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
  def umlClients: List[String⇒Unit] = Nil
}

trait ServerApp extends ExecutableApp with ProtocolsApp with AssemblesApp with DataDependenciesApp with InitialObserversApp with ToInjectApp {
  def execution: Execution
  def rawQSender: RawQSender
  def txObserver: Option[Observer]
  def umlClients: List[String⇒Unit]
  //
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val txTransforms: TxTransforms = new TxTransforms(qMessages)
  lazy val byPriority: ByPriority = ByPriorityImpl
  lazy val preHashing: PreHashing = PreHashingImpl
  lazy val rawWorldFactory: RawWorldFactory = new RichRawWorldFactory(contextFactory,qMessages,getClass.getName)
  lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(initialObservers, new CompletingRawObserver(execution))))
  lazy val contextFactory = new ContextFactory(toInject)
  def indexValueMergerFactory: IndexValueMergerFactory = new SimpleIndexValueMergerFactory
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl(indexValueMergerFactory)
  private lazy val treeAssembler: TreeAssembler = new TreeAssemblerImpl(byPriority,umlClients)
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  private lazy val localQAdapterRegistryInit = new LocalQAdapterRegistryInit(qAdapterRegistry)
  private lazy val assemblerInit =
    new AssemblerInit(qAdapterRegistry, qMessages, treeAssembler, ()⇒dataDependencies)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols.distinct) ::: super.dataDependencies
  override def initialObservers: List[Observer] = txObserver.toList ::: super.initialObservers
  override def toInject: List[ToInject] = assemblerInit :: localQAdapterRegistryInit :: super.toInject
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
