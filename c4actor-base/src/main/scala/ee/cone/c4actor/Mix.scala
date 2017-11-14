
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

trait InitialObserversApp {
  def initialObservers: List[Observer] = Nil
}

trait ProtocolsApp {
  def protocols: List[Protocol] = Nil
  def `the List of Protocol` = protocols
}

/*
trait ToInjectApp extends `The ToInject` {
  def toInject: List[ToInject] = Nil
  override def `the List of ToInject`: List[ToInject] = toInject ::: super.`the List of ToInject`
}*/

trait TreeIndexValueMergerFactoryApp extends `The TreeIndexValueMergerFactory` with `The IndexValueMergerConfigImpl`

trait ServerApp extends RichDataApp with RichObserverApp

trait RichObserverApp extends ExecutableApp with InitialObserversApp with `The TxTransformsImpl` {
  def execution: Execution
  def `the RawQSender`: RawQSender
  def txObserver: Option[Observer]
  def `the QAdapterRegistry`: QAdapterRegistry
  //
  lazy val `the QMessages`: QMessages = new QMessagesImpl(`the QAdapterRegistry`, ()⇒`the RawQSender`)
  lazy val `the ProgressObserverFactory`: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(txObserver.toList ::: initialObservers, new CompletingRawObserver(execution))))
}

trait RichDataApp extends QAdapterRegistryApp
  with `The Assemble`
  with `The ToInject`
  with `The UnitExpressionsDumper`
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
  with `The ProtocolsAssemble`
{
  lazy val `the RawWorldFactory`: RawWorldFactory = new RichRawWorldFactory(`the ContextFactory`,`the ToUpdate`,getClass.getName)

  override def `the List of ToInject`: List[ToInject] =
    new AssemblerInit(`the QAdapterRegistry`, `the ToUpdate`, `the TreeAssembler`, `the IndexFactory`, ()⇒`the List of Assemble`) ::
      super.`the List of ToInject`
}

trait QAdapterRegistryApp extends ProtocolsApp {
  lazy val `the QAdapterRegistry`: QAdapterRegistry = QAdapterRegistryFactory(protocols.distinct)
  override def protocols: List[Protocol] = QProtocol :: super.protocols
}

trait SnapshotMakingApp extends ExecutableApp with QAdapterRegistryApp with `The SnapshotMakingRawWorldFactory` {
  def execution: Execution
  //def `the RawSnapshot`: RawSnapshot
  def snapshotMakingRawObserver: RawObserver //new SnapshotMakingRawObserver(rawSnapshot, new CompletingRawObserver(execution))
  //
  lazy val `the ProgressObserverFactory`: ProgressObserverFactory =
    new ProgressObserverFactoryImpl(snapshotMakingRawObserver)
}

trait VMExecutionApp {
  def `the List of Executable`: List[Executable]
  lazy val execution: Execution = new VMExecution(()⇒`the List of Executable`)
}

trait FileRawSnapshotApp extends `The FileRawSnapshotImpl` with `The RawSnapshotConfigImpl`

trait SerialObserversApp {
  def `the TxTransforms`: TxTransforms
  lazy val txObserver = Option(new SerialObserver(Map.empty)(`the TxTransforms`))
}

trait ParallelObserversApp {
  def execution: Execution
  def `the TxTransforms`: TxTransforms
  lazy val txObserver = Option(new ParallelObserver(Map.empty,`the TxTransforms`,execution))
}

