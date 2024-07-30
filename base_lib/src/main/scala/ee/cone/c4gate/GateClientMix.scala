package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4actor_xml.S3ListerApp
import ee.cone.c4di.c4

trait ActorAccessAppBase
trait ManagementAppBase extends ActorAccessApp /*with PrometheusApp*/ with SyncTxFactoryImplApp
// trait PrometheusAppBase extends DefPublishFullCompressorApp

trait AvailabilityAppBase

trait DefPublishFullCompressorAppBase
trait PublishingCompAppBase extends PublisherApp with DefPublishFullCompressorApp
@c4("DefPublishFullCompressorApp") final class DefPublishFullCompressor extends PublishFullCompressor(GzipFullRawCompressor())

trait SessionAttrCompAppBase extends SessionDataProtocolApp
trait SessionDataProtocolAppBase

trait AlienProtocolAppBase
trait AuthProtocolAppBase
trait HttpProtocolAppBase
trait TcpProtocolAppBase
trait RoomsConfProtocolAppBase

// def availabilityDefaultUpdatePeriod: Long = 3000
// def availabilityDefaultTimeout: Long = 3000

trait HttpUtilAppBase

trait MergingSnapshotAppBase extends SnapshotLoaderFactoryImplApp with RemoteRawSnapshotLoaderImplApp
trait RemoteRawSnapshotAppBase extends TaskSignerApp with ConfigSimpleSignerApp with RemoteRawSnapshotLoaderImplApp
  with S3RawSnapshotLoaderApp with S3ListerApp with S3ManagerApp with SnapshotUtilImplApp
//  with PrepareApp {
//    def prepare(): Unit = if(isInstanceOf[DisableDefaultRemoteRawSnapshotApp]) () else (
//      new RichDataCompApp with ExecutableApp with VMExecutionApp with EnvConfigCompApp with NoAssembleProfilerCompApp
//        with StartUpSnapshotApp with RemoteRawSnapshotApp
//    ).execution.run()
//  }
trait RemoteRawSnapshotLoaderImplAppBase extends HttpUtilApp

trait DefaultMetricsAppBase
trait PrometheusPostAppBase extends DefaultMetricsApp with HttpUtilApp

trait PublisherAppBase extends HttpProtocolApp

trait InjectionAppBase extends ConfigSimpleSignerApp

trait SnapshotPutAppBase extends SignedReqUtilImplApp with SnapshotLoaderFactoryImplApp
trait SignedReqUtilImplAppBase

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -Hx-r-world-key:$worldKey -Hx-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -Hx-r-world-key:SrcId,TxTransform -Hx-r-selection:all
*
* */

trait DevConfigAppBase
