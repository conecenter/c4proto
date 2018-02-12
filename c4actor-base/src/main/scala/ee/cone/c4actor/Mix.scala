
package ee.cone.c4actor

import ee.cone.c4assemble._

/*
trait ToInjectApp extends `The ToInject` {
  def toInject: List[ToInject] = Nil
  override def `the List of ToInject`: List[ToInject] = toInject ::: super.`the List of ToInject`
}*/

trait ActorNameApp {
  lazy val `the ActorName`: ActorName = ActorName(getClass.getName)
}

trait CompoundExecutableApp extends ExecutableApp {
  def execution = `the Execution`
  def `the Execution`: Execution
}

////

trait TreeIndexValueMergerFactoryApp extends `The TreeIndexValueMergerFactory` with `The IndexValueMergerConfigImpl`

trait ServerApp extends RichDataApp with RichObserverApp

trait RichObserverApp extends CompoundExecutableApp with `The TxTransformsImpl` with `The QMessagesImpl`
  with `The ProgressObserverFactoryImpl` with `The RichRawObserverTreeFactory` with `The CompletingRawObserverFactoryImpl`

trait RichDataApp extends `The QProtocol`
  with `The QAdapterRegistryImpl`
  with `The Assembled`
  with `The ToInject`
  with `The UnitExpressionsDumper`
  with `The ByPKFactoryImpl`
  with `The ByUKFactoryImpl`
  with `The ByFKFactoryImpl`
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
  with `The RichRawWorldFactory`
  with `The MortalAssembles`
  with `The Mortal`
  with `The AssemblerInit`
  with ActorNameApp
  with `The SyncTxFactoryImpl`

trait SnapshotMakingApp extends CompoundExecutableApp
  with `The QProtocol` with `The QAdapterRegistryImpl`
  with `The SnapshotMakingRawWorldFactory` with `The ProgressObserverFactoryImpl`

trait FileRawSnapshotApp extends `The FileRawSnapshotImpl` with `The FileRawSnapshotConfigImpl`
