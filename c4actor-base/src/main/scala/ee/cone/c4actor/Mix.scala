
package ee.cone.c4actor

import ee.cone.c4proto.Protocol


trait QMessagesApp extends ProtocolsApp {
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  def rawQSender: RawQSender
  def setOffset(task: Object, offset: Long): Object
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender, setOffset)
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