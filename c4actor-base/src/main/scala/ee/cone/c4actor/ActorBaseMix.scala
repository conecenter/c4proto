
package ee.cone.c4actor

import ee.cone.c4assemble._
import ee.cone.c4proto.{AbstractComponents, c4, provide}

trait BaseAppBase
trait BigDecimalAppBase
trait ConfigSimpleSignerAppBase
trait EnvConfigCompAppBase
trait GzipRawCompressorAppBase
trait MergingSnapshotAppBase extends SnapshotLoaderFactoryImplApp
trait ModelAccessFactoryAppBase
trait MortalFactoryCompAppBase
trait NoAssembleProfilerAppBase
trait NoObserversAppBase
trait ParallelObserversAppBase
trait ProtoAppBase
trait RemoteRawSnapshotAppBase extends TaskSignerApp with ConfigSimpleSignerApp //?SnapshotUtilImplApp
trait RichDataCompAppBase extends BaseApp with ProtoApp with AssembleApp
trait SerialObserversAppBase
trait ServerCompAppBase extends RichDataCompApp with ExecutableApp with SnapshotLoaderImplApp
trait SimpleAssembleProfilerCompAppBase
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

@c4("ServerCompApp") class DefProgressObserverFactoryImpl(
  initialObserverProviders: List[InitialObserverProvider],
  execution: Execution
) {
  @provide def get: Seq[ProgressObserverFactory] = List(
    new ProgressObserverFactoryImpl(
      new StatsObserver(
        new RichRawObserver(
          initialObserverProviders.flatMap(_.option),
          new CompletingRawObserver(execution)
        )
      )
    )
  )
}
