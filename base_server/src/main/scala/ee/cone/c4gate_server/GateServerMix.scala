package ee.cone.c4gate_server

import ee.cone.c4actor_kafka_impl.{KafkaConsumerApp, KafkaProducerApp, KafkaPurgerApp, LZ4RawCompressorApp}
import ee.cone.c4actor_logback_impl.BasicLoggingApp
import ee.cone.c4actor._
import ee.cone.c4di.{c4, c4app, provide}
import ee.cone.c4gate._

@c4app class NoOpApp extends VMExecutionApp with ExecutableApp with BaseApp

trait S3RawSnapshotLoaderAppBase
trait S3RawSnapshotSaverAppBase
trait NoProxySSEConfigAppBase
trait SafeToRunAppBase
trait WorldProviderAppBase

abstract class AbstractHttpGatewayAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp with KafkaPurgerApp
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
  pongProxyHandler: PongProxyHandler,
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
          pongProxyHandler.wire(pongHandler.wire(
            notFoundProtectionHttpHandler.wire(
              selfDosProtectionHttpHandler.wire(
                authHttpHandler.wire(
                  defSyncHttpHandler.wire
                )
              )
            )
          ))
        )
      )
    )
  )
}

//()//todo secure?

@c4("SnapshotMakingApp") final class DefSnapshotSavers(factory: SnapshotSaverImplFactory)
  extends SnapshotSavers(factory.create("snapshots"), factory.create("snapshot_txs"))

trait SnapshotMakingAppBase extends TaskSignerApp
  with S3RawSnapshotLoaderApp with S3RawSnapshotSaverApp
  with S3ManagerApp with SignedReqUtilImplApp
  with ConfigSimpleSignerApp with SnapshotUtilImplApp
  with SnapshotListProtocolApp
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
) {
  @provide def getTcpServer: Seq[TcpServer] = Seq(inner)
  @provide def getExecutable: Seq[Executable] = Seq(inner)
}

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content

//provide httpHandler: FHttpHandler
