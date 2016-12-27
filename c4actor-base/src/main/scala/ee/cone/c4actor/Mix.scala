
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

trait TxTransformsApp {
  def txTransforms: List[TxTransform] = Nil
}

trait QMessagesApp extends ProtocolsApp {
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  def rawQSender: RawQSender
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
}

trait QReducerApp {
  def treeAssembler: TreeAssembler
  def qMessages: QMessages
  lazy val qReducer: Reducer =
    new ReducerImpl(qMessages, treeAssembler)
}

trait ServerApp {
  def toStart: List[Executable]
  lazy val execution: Executable = new ExecutionImpl(toStart)
}

trait EnvConfigApp {
  lazy val config: Config = new EnvConfigImpl
}

////

trait TreeAssemblerApp extends DataDependenciesApp {
  def protocols: List[Protocol]
  lazy val indexFactory: IndexFactory = new IndexFactoryImpl
  override def dataDependencies: List[DataDependencyTo[_]] =
    ProtocolDataDependencies(protocols) ::: super.dataDependencies
  lazy val treeAssembler: TreeAssembler = TreeAssemblerImpl(dataDependencies)
}

////

trait ProtocolsApp {
  def protocols: List[Protocol] = Nil
}

trait SerialObserversApp extends InitialObserversApp {
  def txTransforms: List[TxTransform]
  def qMessages: QMessages
  def qReducer: Reducer
  private lazy val serialObservers = txTransforms.map(new SerialObserver(0)(qMessages,qReducer,_))
  override def initialObservers: List[Observer] = serialObservers ::: super.initialObservers
}