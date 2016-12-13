package ee.cone.c4proto


trait QMessagesApp extends ProtocolsApp {
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  def rawQSender: RawQSender
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
}

trait QReducerApp {
  def messageMappers: List[MessageMapper[_]]
  def treeAssembler: TreeAssembler
  def qAdapterRegistry: QAdapterRegistry
  def qMessages: QMessages
  lazy val qReducers: Map[ActorName,Reducer] =
    QMessageMapperFactory(qAdapterRegistry, qMessages, messageMappers).map{
      case (actorName, qMessageMapper) ⇒
        actorName → new ReducerImpl(actorName)(qMessages, qMessageMapper, treeAssembler)
    }
}

trait ServerApp {
  def toStart: List[CanStart]
  lazy val serverFactory: ServerFactory = new ServerFactoryImpl
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