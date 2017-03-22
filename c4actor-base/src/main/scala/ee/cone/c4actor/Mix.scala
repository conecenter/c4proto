
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

trait ServerApp extends ProtocolsApp with AssemblesApp with DataDependenciesApp with InitialObserversApp {
  def toStart: List[Executable]
  def rawQSender: RawQSender
  def initLocals: List[InitLocal]
  def txObserver: Option[Observer]
  //
  lazy val execution: Executable = new ExecutionImpl(toStart)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val qReducer: Reducer = new ReducerImpl(qMessages, treeAssembler, ()⇒dataDependencies)
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols.distinct)
  lazy val txTransforms: TxTransforms = new TxTransforms(qMessages,qReducer,initLocals)
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl
  private lazy val treeAssembler: TreeAssembler = TreeAssemblerImpl
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols.distinct) ::: super.dataDependencies
  override def initialObservers: List[Observer] = txObserver.toList ::: super.initialObservers
}

trait SerialObserversApp {
  def txTransforms: TxTransforms
  lazy val txObserver = Option(new SerialObserver(Map.empty)(txTransforms))
}

trait ParallelObserversApp {
  def txTransforms: TxTransforms
  lazy val txObserver = Option(new ParallelObserver(Map.empty)(txTransforms))
}
