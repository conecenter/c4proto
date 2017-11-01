
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
  def toInject: List[ToInject] = Nil
  override def `the List of ToInject`: List[ToInject] = toInject ::: super.`the List of ToInject`
}

trait TreeIndexValueMergerFactoryApp {
  def `the IndexValueMergerFactory`: IndexValueMergerFactory =
    new TreeIndexValueMergerFactory(16)
}

trait ServerApp extends RichDataApp with RichObserverApp

trait RichObserverApp extends ExecutableApp with InitialObserversApp with `The TxTransformsImpl` {
  def execution: Execution
  def rawQSender: RawQSender
  def txObserver: Option[Observer]
  def `the QAdapterRegistry`: QAdapterRegistry
  //
  lazy val `the QMessages`: QMessages = new QMessagesImpl(`the QAdapterRegistry`, ()⇒rawQSender)
  lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(initialObservers, new CompletingRawObserver(execution))))
  override def initialObservers: List[Observer] = txObserver.toList ::: super.initialObservers
}

trait RichDataApp extends ProtocolsApp with AssemblesApp
  with `The Assemble`
  with `The ToInject`
  with `The UnitExpressionsDumper`
  with `The DataDependencyTo`
  with `The ByUKGetterFactoryImpl`
  with `The JoinKeyFactoryImpl`
  with `The ModelConditionFactoryImpl`
  with `The HashSearchFactoryImpl`
  with `The ToUpdateImpl`
  with `The ByPriorityImpl`
  with `The PreHashingImpl`
  with `The ContextFactoryImpl`
  with `The DefaultModelRegistryImpl`
  with `The DefaultModelFactory`
  with `The IndexFactoryImpl`
  with `The TreeAssemblerImpl`
  with `The LocalQAdapterRegistryInit`
{
  lazy val `the QAdapterRegistry`: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val `the RawWorldFactory`: RawWorldFactory = new RichRawWorldFactory(`the ContextFactory`,`the ToUpdate`,getClass.getName)
  private lazy val assemblerInit =
    new AssemblerInit(`the QAdapterRegistry`, `the ToUpdate`, `the TreeAssembler`, ()⇒`the List of DataDependencyTo`)
  //
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  override def `the List of DataDependencyTo`: List[DataDependencyTo[_]] =
    AssembleDataDependencies(`the IndexFactory`,`the List of Assemble`) :::
    ProtocolDataDependencies(protocols.distinct) :::
    super.`the List of DataDependencyTo`
  override def `the List of ToInject`: List[ToInject] =
    assemblerInit ::
    super.`the List of ToInject`
}

trait SnapshotMakingApp extends ExecutableApp with ProtocolsApp with `The SnapshotMakingRawWorldFactory` {
  def execution: Execution
  //def `the RawSnapshot`: RawSnapshot
  def snapshotMakingRawObserver: RawObserver //new SnapshotMakingRawObserver(rawSnapshot, new CompletingRawObserver(execution))
  //
  lazy val `the QAdapterRegistry`: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  lazy val progressObserverFactory: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(snapshotMakingRawObserver)
  override def protocols: List[Protocol] = QProtocol :: super.protocols
}

trait VMExecutionApp {
  def toStart: List[Executable]
  lazy val execution: Execution = new VMExecution(()⇒toStart)
}

trait FileRawSnapshotApp extends `The FileRawSnapshotImpl` {
  lazy val `the RawSnapshotConfig` = RawSnapshotConfig("db4/snapshots")
}

trait SerialObserversApp {
  def `the TxTransforms`: TxTransforms
  lazy val txObserver = Option(new SerialObserver(Map.empty)(`the TxTransforms`))
}

trait ParallelObserversApp {
  def execution: Execution
  def `the TxTransforms`: TxTransforms
  lazy val txObserver = Option(new ParallelObserver(Map.empty,`the TxTransforms`,execution))
}

