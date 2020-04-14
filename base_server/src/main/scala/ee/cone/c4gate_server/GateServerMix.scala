package ee.cone.c4gate_server

import ee.cone.c4actor_kafka_impl.{KafkaConsumerApp, KafkaProducerApp, LZ4RawCompressorApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4actor._
import ee.cone.c4di.{c4, c4app, provide}
import ee.cone.c4gate._

@c4app class NoOpApp extends VMExecutionApp with ExecutableApp with BaseApp

@c4app class IgnoreAllSnapshotsAppBase extends EnvConfigCompApp with VMExecutionApp with NoAssembleProfilerCompApp
  with ExecutableApp with RichDataCompApp with KafkaConsumerApp
  with SnapshotUtilImplApp with FileRawSnapshotSaverApp with ConfigDataDirApp

/*
@c4app class PublishAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with PublishingCompApp
  with NoAssembleProfilerCompApp
  with RemoteRawSnapshotApp
  with NoObserversApp*/

////

trait ConfigDataDirAppBase
trait FileRawSnapshotLoaderAppBase
trait FileRawSnapshotSaverAppBase
trait NoProxySSEConfigAppBase
trait SafeToRunAppBase
trait WorldProviderAppBase

abstract class AbstractHttpGatewayAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with PublisherApp with AuthProtocolApp
  with SSEServerApp
  with NoAssembleProfilerCompApp
  with MortalFactoryCompApp
  with ManagementApp
  with SnapshotMakingApp
  with SnapshotPutApp
  with LZ4RawCompressorApp
  with BasicLoggingApp
  with NoProxySSEConfigApp
  with SafeToRunApp
  with WorldProviderApp
  with SkipWorldPartsApp

@c4("AbstractHttpGatewayApp") final class DefFHttpHandlerProvider(
  fHttpHandlerFactory: FHttpHandlerImplFactory,
  httpGetSnapshotHandler: HttpGetSnapshotHandler,
  getPublicationHttpHandler: GetPublicationHttpHandler,
  pongHandler: PongHandler,
  notFoundProtectionHttpHandler: NotFoundProtectionHttpHandler,
  selfDosProtectionHttpHandler: SelfDosProtectionHttpHandler,
  authHttpHandler: AuthHttpHandler,
  defSyncHttpHandler: DefSyncHttpHandler
){
  @provide def get: Seq[FHttpHandler] = List(
    fHttpHandlerFactory.create(
      httpGetSnapshotHandler.wire(
        getPublicationHttpHandler.wire(
          pongHandler.wire(
            notFoundProtectionHttpHandler.wire(
              selfDosProtectionHttpHandler.wire(
                authHttpHandler.wire(
                  defSyncHttpHandler.wire
                )
              )
            )
          )
        )
      )
    )
  )
}

//()//todo secure?

@c4("SnapshotMakingApp") final class DefSnapshotSavers(factory: SnapshotSaverImplFactory)
  extends SnapshotSavers(factory.create("snapshots"), factory.create("snapshot_txs"))

trait SnapshotMakingAppBase extends TaskSignerApp
  with FileRawSnapshotLoaderApp with FileRawSnapshotSaverApp
  with ConfigDataDirApp with SignedReqUtilImplApp
  with ConfigSimpleSignerApp with SnapshotUtilImplApp
trait SnapshotPutAppBase extends SignedReqUtilImplApp with SnapshotLoaderFactoryImplApp
trait SignedReqUtilImplAppBase

trait SSEServerAppBase extends AlienProtocolApp



@c4("SSEServerApp") final class SSEServer(
  config: Config,
  sseConfig: SSEConfig
)(
  ssePort: Int = config.get("C4SSE_PORT").toInt
)(
  inner: TcpServerImpl = new TcpServerImpl(ssePort, new SSEHandler(sseConfig), 10, new GzipStreamCompressorFactory)
) extends Executable with ToInject {
  def toInject: List[Injectable] = inner.toInject
  def run(): Unit = inner.run()
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content

//provide httpHandler: FHttpHandler
