package ee.cone.c4actor

import ee.cone.c4actor.ComponentRegistry.provide
import ee.cone.c4assemble.{Assemble, AssembleSeqOptimizer, BackStageFactory, ExpressionsDumper, IndexUpdater, IndexUtil, NoAssembleSeqOptimizer, ReadModelUtil, UMLExpressionsDumper}
import ee.cone.c4proto.{Component, ComponentsApp, Protocol}

trait ProtocolsApp extends ComponentsApp {
  override def components: List[Component] =
    protocols.distinct.flatMap(_.components) :::
      super.components

  def protocols: List[Protocol] = Nil
}

trait ToStartApp extends ComponentsApp {
  private lazy val executableComponent = ComponentRegistry.provide(classOf[Executable], Nil, ()=>toStart)
  override def components: List[Component] = executableComponent :: super.components
  def toStart: List[Executable] = Nil
}

trait AssemblesApp extends ComponentsApp {
  private lazy val assembleComponent = ComponentRegistry.provide(classOf[Assemble], Nil, ()=>assembles)
  override def components: List[Component] = assembleComponent :: super.components
  def assembles: List[Assemble] = Nil
}

trait ToInjectApp extends ComponentsApp {
  private lazy val toInjectComponent = ComponentRegistry.provide(classOf[ToInject], Nil, ()=>toInject)
  override def components: List[Component] = toInjectComponent :: super.components
  def toInject: List[ToInject] = Nil
}

trait PreHashingApp {
  def preHashing: PreHashing
}

trait ServerApp extends ServerCompApp with RichDataApp { //e-only
  import componentRegistry.resolveSingle
  lazy val snapshotLoader: SnapshotLoader = resolveSingle(classOf[SnapshotLoader])
  lazy val qMessages: QMessages = resolveSingle(classOf[QMessages])
  lazy val consuming: Consuming = resolveSingle(classOf[Consuming])
  lazy val rawQSender: RawQSender = resolveSingle(classOf[RawQSender])
  //
  lazy val snapshotTaskSigner: Signer[SnapshotTask] = resolveSingle(classOf[SnapshotTaskSigner])
  lazy val remoteSnapshotUtil: RemoteSnapshotUtil = resolveSingle(classOf[RemoteSnapshotUtil])
  lazy val snapshotMaker: SnapshotMaker = resolveSingle(classOf[SnapshotMaker])
  lazy val rawSnapshotLoader: RawSnapshotLoader = resolveSingle(classOf[RawSnapshotLoader])
}

trait TestVMRichDataApp extends TestVMRichDataCompApp with RichDataApp with ToStartApp {
  import componentRegistry.resolveSingle
  lazy val contextFactory = resolveSingle(classOf[ContextFactory]) // extra-only
  lazy val actorName: String = getClass.getName
}

trait MortalFactoryApp extends MortalFactoryCompApp {
  def componentRegistry: ComponentRegistry
  def mortal: MortalFactory = componentRegistry.resolveSingle(classOf[MortalFactory])
}

@deprecated trait SimpleIndexValueMergerFactoryApp
@deprecated trait TreeIndexValueMergerFactoryApp

trait RichDataApp extends RichDataCompApp with AssembleProfilerApp with DefaultKeyFactoryApp with DefaultUpdateProcessorApp with ExpressionsDumpersApp {
  import componentRegistry.resolveSingle
  lazy val qAdapterRegistry: QAdapterRegistry = resolveSingle(classOf[QAdapterRegistry])
  lazy val toUpdate: ToUpdate = resolveSingle(classOf[ToUpdate])
  lazy val preHashing: PreHashing = resolveSingle(classOf[PreHashing])
  lazy val richRawWorldReducer: RichRawWorldReducer = resolveSingle(classOf[RichRawWorldReducer])
  lazy val indexUtil: IndexUtil = resolveSingle(classOf[IndexUtil])
  lazy val idGenUtil: IdGenUtil = resolveSingle(classOf[IdGenUtil])
  lazy val defaultModelRegistry: DefaultModelRegistry = resolveSingle(classOf[DefaultModelRegistry])
  lazy val readModelUtil: ReadModelUtil = resolveSingle(classOf[ReadModelUtil])
  lazy val indexUpdater: IndexUpdater = resolveSingle(classOf[IndexUpdater])
  lazy val backStageFactory: BackStageFactory = resolveSingle(classOf[BackStageFactory])
  lazy val hashSearchFactory: HashSearch.Factory = resolveSingle(classOf[HashSearchFactoryHolder]).value
  lazy val modelConditionFactory: ModelConditionFactory[Unit] = resolveSingle(classOf[ModelConditionFactoryHolder]).value

  @deprecated def parallelAssembleOn: Boolean = false
  @deprecated def assembleSeqOptimizer: AssembleSeqOptimizer = new NoAssembleSeqOptimizer
}

trait AssembleProfilerApp extends ComponentsApp {
  def assembleProfiler: AssembleProfiler
  private lazy val assembleProfilerComponent =
    provide(classOf[AssembleProfiler],Nil,()=>List(assembleProfiler))
  override def components: List[Component] = assembleProfilerComponent :: super.components
}

trait DefaultKeyFactoryApp extends ComponentsApp {
  def origKeyFactoryOpt: Option[KeyFactory] = None
  private lazy val origKeyFactoryComponent =
    provide(classOf[OrigKeyFactoryProposition],Nil,()=>origKeyFactoryOpt.map(new OrigKeyFactoryProposition(_)).toList)
  override def components: List[Component] = origKeyFactoryComponent :: super.components
}

trait DefaultUpdateProcessorApp extends ComponentsApp {
  def updateProcessor: UpdateProcessor = new DefaultUpdateProcessor
  private lazy val updateProcessorComponent =
    provide(classOf[UpdateProcessor],Nil,()=>List(updateProcessor))
  override def components: List[Component] = updateProcessorComponent :: super.components
}

trait ExpressionsDumpersApp extends ComponentsApp {
  private lazy val expressionsDumpersComponent = ComponentRegistry.provide(classOf[ExpressionsDumper[Unit]], List(ComponentRegistry.toTypeKey(classOf[Unit],Nil)), ()=>expressionsDumpers)
  override def components: List[Component] = expressionsDumpersComponent :: super.components
  def expressionsDumpers: List[ExpressionsDumper[Unit]] = Nil
}

trait UMLClientsApp {
  lazy val umlExpressionsDumper: ExpressionsDumper[String] = UMLExpressionsDumper
}

trait EnvConfigApp extends EnvConfigCompApp {
  def componentRegistry: ComponentRegistry
  lazy val config: Config = componentRegistry.resolveSingle(classOf[Config])
  lazy val actorName: String = componentRegistry.resolveSingle(classOf[ActorName]).value
}

trait UpdatesProcessorsApp extends ComponentsApp {
  private lazy val processorsComponent = ComponentRegistry.provide(classOf[UpdatesPreprocessor], Nil, ()=>processors)
  override def components: List[Component] = processorsComponent :: super.components
  def processors: List[UpdatesPreprocessor] = Nil
}

trait DefaultModelFactoriesApp extends ComponentsApp {
  private lazy val defaultModelFactoriesComponent = ComponentRegistry.provide(classOf[DefaultModelFactory[_]], Nil, ()=>defaultModelFactories)
  override def components: List[Component] = defaultModelFactoriesComponent :: super.components
  def defaultModelFactories: List[DefaultModelFactory[_]] = Nil
}

trait FileRawSnapshotApp extends RemoteRawSnapshotApp  // Remote!



trait FromExternalDBSyncApp extends ee.cone.c4actor.rdb_impl.FromExternalDBSyncApp
trait ToExternalDBSyncApp extends ee.cone.c4actor.rdb_impl.ToExternalDBSyncApp
trait RDBSyncApp extends ComponentsApp with ExternalDBOptionsApp { // may be to other module
  def componentRegistry: ComponentRegistry
  lazy val rdbOptionFactory: RDBOptionFactory = componentRegistry.resolveSingle(classOf[RDBOptionFactory])
  lazy val externalDBSyncClient: ExternalDBClient = componentRegistry.resolveSingle(classOf[ExternalDBClient])

  def externalDBFactory: ExternalDBFactory
  private lazy val externalDBFactoryComponent = ComponentRegistry.provide(classOf[ExternalDBFactory],Nil,()=>List(externalDBFactory))
  override def components: List[Component] = externalDBFactoryComponent :: super.components
}
trait ExternalDBOptionsApp extends ComponentsApp{
  def externalDBOptions: List[ExternalDBOption] = Nil
  private lazy val externalDBOptionsComponent = ComponentRegistry.provide(classOf[ExternalDBOptionHolder],Nil,()=>List(new ExternalDBOptionHolder(externalDBOptions)))
  override def components: List[Component] = externalDBOptionsComponent :: super.components
}



// def externalDBFactory: ExternalDBFactory //provide
// externalDBOptions: List[ExternalDBOption] //provide