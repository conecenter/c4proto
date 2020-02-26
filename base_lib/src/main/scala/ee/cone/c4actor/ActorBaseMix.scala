
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4di.AbstractComponents

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
trait RichDataCompAppBase extends BaseApp with ProtoApp with AssembleApp
trait SerialObserversAppBase
trait ServerCompAppBase extends RichDataCompApp with ExecutableApp with SnapshotLoaderImplApp
trait SimpleAssembleProfilerCompAppBase
trait SkipWorldPartsAppBase
trait SnapshotLoaderFactoryImplAppBase
trait SnapshotLoaderImplAppBase
trait SnapshotUtilImplAppBase
trait SyncTxFactoryImplAppBase
trait TaskSignerAppBase
trait TestVMRichDataCompAppBase extends RichDataCompApp with VMExecutionApp with EnvConfigCompApp

trait VMExecutionAppBase extends AbstractComponents {
  lazy val componentRegistry = ComponentRegistry(this)
  lazy val execution: Execution = Single(componentRegistry.resolve(classOf[Execution],Nil).value)
}
