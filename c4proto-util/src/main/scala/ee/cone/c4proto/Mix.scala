package ee.cone.c4proto


trait QMessagesApp extends ProtocolsApp {
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  def messageMappers: List[MessageMapper[_]]
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry)
  lazy val qMessageMapper: QMessageMapper = QMessageMapperImpl(qAdapterRegistry, messageMappers)
}

trait ServerApp {
  def toStart: List[CanStart]
  lazy val serverFactory: ServerFactory = new ServerFactoryImpl
  lazy val execution: Runnable = new ExecutionImpl(toStart)
}

////

trait TreeAssemblerApp extends DataDependenciesApp {
  def protocols: List[Protocol]
  lazy val indexFactory: IndexFactory = new IndexFactoryImpl
  override def dataDependencies: List[DataDependencyTo[_]] =
    ProtocolDataDependencies(protocols) ::: super.dataDependencies
  lazy val treeAssembler: TreeAssembler = TreeAssemblerImpl(dataDependencies)
}
