
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4di.{AbstractComponents, c4app}

trait BaseAppBase
trait BigDecimalAppBase
trait ConfigSimpleSignerAppBase
trait EnvConfigCompAppBase
trait GzipRawCompressorAppBase
trait ModelAccessFactoryCompAppBase
trait MortalFactoryCompAppBase
trait NoAssembleProfilerCompAppBase
trait NoObserversAppBase
trait ParallelObserversAppBase
trait ProtoAppBase
trait RichDataCompAppBase extends BaseApp with ProtoApp with AssembleApp with CatchNonFatalApp // with ProductCheckerApp
trait SerialObserversAppBase
trait ServerCompAppBase extends RichDataCompApp with ExecutableApp with SnapshotLoaderImplApp with ChildElectorClientApp with LOBrokerApp
trait SimpleAssembleProfilerCompAppBase
trait SkipWorldPartsAppBase
trait SnapshotLoaderFactoryImplAppBase
trait SnapshotLoaderImplAppBase
trait SnapshotUtilImplAppBase
trait SyncTxFactoryImplAppBase
trait TaskSignerAppBase
trait TestVMRichDataCompAppBase extends RichDataCompApp with VMExecutionApp with EnvConfigCompApp
trait CatchNonFatalAppBase

trait VMExecutionAppBase extends AbstractComponents {
  lazy val componentRegistry = ComponentRegistry(this)
  lazy val execution: Execution = Single(componentRegistry.resolve(classOf[Execution],Nil).value)
}

trait DeadlockDetectAppBase

trait HttpClientAppBase

trait ElectorClientAppBase extends HttpClientApp
trait ChildElectorClientAppBase extends ElectorClientApp
@c4app class ParentElectorClientAppBase extends ElectorClientApp with ExecutableApp with VMExecutionApp with BaseApp with EnvConfigCompApp

trait ScalingAppBase extends ChildElectorClientApp
trait ScalingTestAppBase

trait S3ManagerAppBase extends HttpClientApp

trait LOBrokerAppBase extends S3ManagerApp

trait S3RawSnapshotLoaderAppBase

trait ProductCheckerAppBase

trait StartUpSnapshotAppBase