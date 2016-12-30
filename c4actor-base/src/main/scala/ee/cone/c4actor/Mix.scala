
package ee.cone.c4actor

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

trait EnvConfigApp {
  lazy val config: Config = new EnvConfigImpl
}

trait ServerApp extends ProtocolsApp with DataDependenciesApp {
  def toStart: List[Executable]
  def rawQSender: RawQSender
  //
  lazy val execution: Executable = new ExecutionImpl(toStart)
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val qReducer: Reducer = new ReducerImpl(qMessages, treeAssembler, ()⇒dataDependencies)
  lazy val indexFactory: IndexFactory = new IndexFactoryImpl
  lazy val treeAssembler: TreeAssembler = TreeAssemblerImpl
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    ProtocolDataDependencies(protocols) ::: super.dataDependencies
}

trait SerialObserversApp extends InitialObserversApp {
  def qMessages: QMessages
  def qReducer: Reducer
  private lazy val serialObserver = new SerialObserver(Map.empty)(qMessages,qReducer)
  override def initialObservers: List[Observer] = serialObserver :: super.initialObservers
}
