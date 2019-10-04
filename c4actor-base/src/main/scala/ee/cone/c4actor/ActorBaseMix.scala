
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4proto.{AbstractComponents, c4}

trait BaseAppBase
trait BigDecimalAppBase
trait ConfigSimpleSignerAppBase
trait EnvConfigCompAppBase
trait GzipRawCompressorAppBase
trait ManagementAppBase
trait MergingSnapshotAppBase extends SnapshotLoaderFactoryImplApp
trait MortalFactoryCompAppBase
trait NoAssembleProfilerAppBase
trait NoObserversAppBase
trait ParallelObserversAppBase
trait ProtoAppBase
trait RemoteRawSnapshotAppBase extends TaskSignerApp with ConfigSimpleSignerApp
trait RichDataCompAppBase extends BaseApp with ProtoApp with AssembleApp
trait SerialObserversAppBase
trait ServerCompAppBase extends RichDataCompApp with ExecutableApp with SnapshotLoaderImplApp
trait SimpleAssembleProfilerAppBase
trait SnapshotLoaderFactoryImplAppBase
trait SnapshotLoaderImplAppBase
trait TaskSignerAppBase
trait TestVMRichDataCompAppBase extends RichDataCompApp with VMExecutionApp

trait VMExecutionAppBase extends AbstractComponents {
  lazy val componentRegistry = ComponentRegistry(this)
  lazy val execution: Execution = componentRegistry.resolveSingle(classOf[Execution])
}

@c4("ServerCompApp") class DefProgressObserverFactoryImpl(
  initialObserverProviders: List[InitialObserverProvider],
  execution: Execution
) extends ProgressObserverFactoryImpl(new StatsObserver(new RichRawObserver(initialObserverProviders.flatMap(_.option), new CompletingRawObserver(execution))))
