
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

trait ServerApp extends ProtocolsApp with AssemblesApp with DataDependenciesApp {
  def toStart: List[Executable]
  def rawQSender: RawQSender
  //
  lazy val execution: Executable = new ExecutionImpl(toStart)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val qReducer: Reducer = new ReducerImpl(qMessages, treeAssembler, ()⇒dataDependencies)
  private lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  private lazy val indexFactory: IndexFactory = new IndexFactoryImpl
  private lazy val treeAssembler: TreeAssembler = TreeAssemblerImpl
  private lazy val assembleDataDependencies = AssembleDataDependencies(indexFactory,assembles)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    assembleDataDependencies :::
    ProtocolDataDependencies(protocols) ::: super.dataDependencies
}

trait SerialObserversApp extends InitialObserversApp {
  def qMessages: QMessages
  def qReducer: Reducer
  def initLocals: List[InitLocal]
  private lazy val serialObserver = new SerialObserver(Map.empty)(qMessages,qReducer,initLocals)
  override def initialObservers: List[Observer] = serialObserver :: super.initialObservers
}
