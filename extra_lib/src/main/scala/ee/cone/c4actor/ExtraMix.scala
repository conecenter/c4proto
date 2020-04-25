package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4actor._
import ee.cone.c4di._

import scala.collection.immutable.{Map, Seq}

object ComponentProvider {
  private def toTypeKey[T](cl: Class[T]): TypeKey =
    CreateTypeKey(cl,cl.getSimpleName,Nil)
  def provide[T<:Object](cl: Class[T], get: ()=>Seq[T]): Component =
    new Component(toTypeKey(cl),None,Nil,_=>get())
  def resolveSingle[T](cl: Class[T])(componentRegistry: ComponentRegistry): T =
    componentRegistry.resolve(cl,Nil).value match {
      case Seq(r) => r
      case r => throw new Exception(s"external resolution of $cl fails with $r")
    }
}
trait ComponentProviderApp {
  def componentRegistry: ComponentRegistry
  def resolveSingle[T](cl: Class[T]): T = ComponentProvider.resolveSingle(cl)(componentRegistry)
}

abstract class AppChecker {
  def executeFor(app: ComponentProviderApp): Unit
}

import ComponentProvider._

trait ToStartApp extends ComponentsApp {
  private lazy val executableComponent = provide(classOf[Executable], ()=>toStart)
  override def components: List[Component] = executableComponent :: super.components
  def toStart: List[Executable] = Nil
}

trait AssemblesApp extends ComponentsApp {
  private lazy val assembleComponent = provide(classOf[Assemble], ()=>assembles)
  override def components: List[Component] = assembleComponent :: super.components
  def assembles: List[Assemble] = Nil
}

trait ToInjectApp extends ComponentsApp {
  private lazy val toInjectComponent = provide(classOf[ToInject], ()=>toInject)
  override def components: List[Component] = toInjectComponent :: super.components
  def toInject: List[ToInject] = Nil
}

trait PreHashingApp {
  def preHashing: PreHashing
}

trait ServerApp extends ServerCompApp with RichDataApp { //e-only
  lazy val snapshotLoader: SnapshotLoader = resolveSingle(classOf[SnapshotLoader])
  lazy val qMessages: QMessages = resolveSingle(classOf[QMessages])
  lazy val consuming: Consuming = resolveSingle(classOf[Consuming])
  lazy val rawQSender: RawQSender = resolveSingle(classOf[RawQSender])
  //
  lazy val remoteSnapshotUtil: RemoteSnapshotUtil = resolveSingle(classOf[RemoteSnapshotUtil])
  lazy val snapshotMaker: SnapshotMaker = resolveSingle(classOf[SnapshotMaker])
  lazy val rawSnapshotLoader: RawSnapshotLoader = resolveSingle(classOf[RawSnapshotLoader])
}

trait TestVMRichDataApp extends TestVMRichDataCompApp
  with RichDataApp
  with ToStartApp
{// extra-only
  lazy val contextFactory: ContextFactory = resolveSingle(classOf[ContextFactory])
  lazy val actorName: String = getClass.getName
}

trait MortalFactoryApp extends MortalFactoryCompApp with ComponentProviderApp {
  def mortal: MortalFactory = resolveSingle(classOf[MortalFactory])
}

@deprecated trait SimpleIndexValueMergerFactoryApp
@deprecated trait TreeIndexValueMergerFactoryApp

trait RichDataAppBase extends RichDataCompApp
  // with AssembleProfilerApp
  with DefaultKeyFactoryApp
  with DefaultUpdateProcessorApp
  with ExpressionsDumpersApp
  with ComponentProviderApp
  with AssemblesApp
{
  lazy val byPriority: ByPriority = resolveSingle(classOf[ByPriority])
  lazy val qAdapterRegistry: QAdapterRegistry = resolveSingle(classOf[QAdapterRegistry])
  lazy val toUpdate: ToUpdate = resolveSingle(classOf[ToUpdate])
  lazy val preHashing: PreHashing = resolveSingle(classOf[PreHashing])
  lazy val richRawWorldReducer: RichRawWorldReducer = resolveSingle(classOf[RichRawWorldReducer])
  lazy val indexUtil: IndexUtil = resolveSingle(classOf[IndexUtil])
  lazy val idGenUtil: IdGenUtil = resolveSingle(classOf[IdGenUtil])
  lazy val modelFactory: ModelFactory = resolveSingle(classOf[ModelFactory])
  lazy val readModelUtil: ReadModelUtil = resolveSingle(classOf[ReadModelUtil])
  lazy val indexUpdater: IndexUpdater = resolveSingle(classOf[IndexUpdater])
  lazy val backStageFactory: BackStageFactory = resolveSingle(classOf[BackStageFactory])
  lazy val hashSearchFactory: HashSearch.Factory = resolveSingle(classOf[HashSearchFactoryHolder]).value
  lazy val modelConditionFactory: ModelConditionFactory[Unit] = resolveSingle(classOf[ModelConditionFactoryHolder]).value

  @deprecated def parallelAssembleOn: Boolean = false
  // @deprecated def assembleSeqOptimizer: AssembleSeqOptimizer = new NoAssembleSeqOptimizer
}

abstract class GeneralCompatHolder {
  def value: Any
}
class CompatHolder[T](val value: T) extends GeneralCompatHolder

@c4("RichDataApp") final class ModelConditionFactoryHolder(value: ModelConditionFactory[Unit])
  extends CompatHolder[ModelConditionFactory[Unit]](value)
/*
trait AssembleProfilerApp extends ComponentsApp {
  def assembleProfiler: AssembleProfiler
  private lazy val assembleProfilerComponent =
    provide(classOf[AssembleProfiler],()=>List(assembleProfiler))
  override def components: List[Component] = assembleProfilerComponent :: super.components
}*/

trait DefaultKeyFactoryApp extends ComponentsApp {
  def origKeyFactoryOpt: Option[KeyFactory] = None
  private lazy val origKeyFactoryComponent =
    provide(classOf[OrigKeyFactoryProposition],()=>origKeyFactoryOpt.map(new OrigKeyFactoryProposition(_)).toList)
  override def components: List[Component] = origKeyFactoryComponent :: super.components
}

trait DefaultUpdateProcessorApp extends ComponentsApp {
  def updateProcessor: UpdateProcessor = new DefaultUpdateProcessor
  private lazy val updateProcessorComponent =
    provide(classOf[UpdateProcessor],()=>List(updateProcessor))
  override def components: List[Component] = updateProcessorComponent :: super.components
}

class ExpressionsDumperHolder(value: ExpressionsDumper[Unit])
  extends CompatHolder[ExpressionsDumper[Unit]](value)

@c4("ExpressionsDumpersApp") final class ExpressionsDumpersProvider(holders: List[ExpressionsDumperHolder]){
  @provide def get: Seq[ExpressionsDumper[Unit]] = holders.map(_.value)
}

trait ExpressionsDumpersAppBase extends ComponentsApp {
  private lazy val expressionsDumpersComponent = provide(classOf[ExpressionsDumperHolder], ()=>expressionsDumpers.map(new ExpressionsDumperHolder(_)))
  override def components: List[Component] = expressionsDumpersComponent :: super.components
  def expressionsDumpers: List[ExpressionsDumper[Unit]] = Nil
}

trait UMLClientsApp {
  lazy val umlExpressionsDumper: ExpressionsDumper[String] = UMLExpressionsDumper
}

trait EnvConfigApp extends EnvConfigCompApp with ComponentProviderApp {
  lazy val config: Config = resolveSingle(classOf[Config])
  lazy val actorName: String = resolveSingle(classOf[ActorName]).value
}

trait UpdatesProcessorsApp extends ComponentsApp {
  private lazy val processorsComponent = provide(classOf[UpdatesPreprocessor], ()=>processors)
  override def components: List[Component] = processorsComponent :: super.components
  def processors: List[UpdatesPreprocessor] = Nil
}

trait SimpleAssembleProfilerApp extends SimpleAssembleProfilerCompApp with ComponentProviderApp {
  def assembleProfiler: AssembleProfiler = resolveSingle(classOf[AssembleProfiler])
}

////
@c4("RichDataApp") final class TxAddInject(
  txAdd: LTxAdd,
  rawTxAdd: RawTxAdd,
) extends ToInject {
  def toInject: List[Injectable] =
    TxAddKey.set(txAdd) ::: WriteModelAddKey.set(rawTxAdd.add)
}
@deprecated case object TxAddKey extends SharedComponentKey[LTxAdd]
@deprecated object TxAdd {
    def apply[M<:Product](out: Seq[LEvent[M]]): Context=>Context = context =>
      TxAddKey.of(context).add(out)(context)
}
@deprecated case object WriteModelAddKey extends SharedComponentKey[Seq[N_Update]=>Context=>Context]