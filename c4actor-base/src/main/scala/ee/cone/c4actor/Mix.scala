
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

trait InitLocalsApp {
  def initLocals: List[InitLocal] = Nil
}

trait EnvConfigApp {
  lazy val config: Config = new EnvConfigImpl
}

trait UMLClientsApp {
  def umlClients: List[String⇒Unit] = Nil
}

trait ServerApp extends ExecutableApp with ProtocolsApp with AssemblesApp with DataDependenciesApp with InitialObserversApp with InitLocalsApp {
  def toStart: List[Executable]
  def rawQSender: RawQSender
  def txObserver: Option[Observer]
  def umlClients: List[String⇒Unit]
  //
  lazy val execution: Executable = new ExecutionImpl(toStart)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val txTransforms: TxTransforms = new TxTransforms(qMessages,qReducer,initLocals)
  lazy val byPriority: ByPriority = ByPriorityImpl
  lazy val preHashing: PreHashing = PreHashingImpl
  lazy val rawObserver: RawObserver = new RichRawObserver(qReducerImpl,initialObservers,None,Nil,Option(0L))
  def qReducer: Reducer = qReducerImpl
  def indexValueMergerFactory: IndexValueMergerFactory = new SimpleIndexValueMergerFactory
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl(indexValueMergerFactory)
  private lazy val treeAssembler: TreeAssembler = new TreeAssemblerImpl(byPriority,umlClients)
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  private lazy val localQAdapterRegistryInit = new LocalQAdapterRegistryInit(qAdapterRegistry)
  private lazy val qReducerImpl: ReducerImpl = new ReducerImpl(qMessages, treeAssembler, ()⇒dataDependencies)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols.distinct) ::: super.dataDependencies
  override def initialObservers: List[Observer] = txObserver.toList ::: super.initialObservers
  override def initLocals: List[InitLocal] = localQAdapterRegistryInit :: super.initLocals
}

trait FileRawSnapshotApp {
  lazy val rawSnapshot: RawSnapshot = new FileRawSnapshotImpl("db4/snapshots")
}

trait SerialObserversApp {
  def txTransforms: TxTransforms
  lazy val txObserver = Option(new SerialObserver(Map.empty)(txTransforms))
}

trait ParallelObserversApp {
  def txTransforms: TxTransforms
  lazy val txObserver = Option(new ParallelObserver(Map.empty)(txTransforms))
}

trait MortalFactoryApp extends AssemblesApp {
  def mortal: MortalFactory = MortalFactoryImpl
}
