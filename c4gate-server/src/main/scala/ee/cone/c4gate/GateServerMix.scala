package ee.cone.c4gate

import ee.cone.c4actor.{BasicLoggingApp, Config, ConfigSimpleSignerApp, EnvConfigCompApp, Executable, GzipStreamCompressorFactory, Injectable, KafkaConsumerApp, KafkaProducerApp, LZ4RawCompressorApp, ManagementApp, MortalFactoryCompApp, NoAssembleProfilerApp, NoObserversApp, ParallelObserversApp, RawSnapshotSaver, RemoteRawSnapshotApp, ServerCompApp, SnapshotLoader, SnapshotLoaderFactoryImplApp, SnapshotSaverImpl, TaskSignerApp, ToInject, VMExecutionApp}
import ee.cone.c4proto.c4component

class PublishAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with PublishingCompApp
  with NoAssembleProfilerApp
  with RemoteRawSnapshotApp
  with NoObserversApp

////

trait ConfigDataDirAppBase
trait FileRawSnapshotLoaderAppBase
trait NoProxySSEConfigAppBase
trait SafeToRunAppBase
trait WorldProviderAppBase

abstract class AbstractHttpGatewayAppBase extends ServerCompApp
  with EnvConfigCompApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with HttpProtocolApp with AuthProtocolApp
  with SSEServerApp
  with NoAssembleProfilerApp
  with MortalFactoryCompApp
  with ManagementApp
  with SnapshotMakingApp
  with SnapshotPutApp
  with LZ4RawCompressorApp
  with BasicLoggingApp
  with NoProxySSEConfigApp
  with SafeToRunApp
  with WorldProviderApp

@c4component("AbstractHttpGatewayApp") class DefFHttpHandlerHolder(
  pongRegistry: PongRegistry,
  loader: SnapshotLoader,
  sseConfig: SSEConfig,
  worldProvider: WorldProvider,
  httpResponseFactory: RHttpResponseFactory
) extends FHttpHandlerHolder(
  new FHttpHandlerImpl(worldProvider, httpResponseFactory,
    new HttpGetSnapshotHandler(loader, httpResponseFactory,
      new GetPublicationHttpHandler(httpResponseFactory,
        new PongHandler(sseConfig, pongRegistry, httpResponseFactory,
          new NotFoundProtectionHttpHandler(httpResponseFactory,
            new SelfDosProtectionHttpHandler(httpResponseFactory, sseConfig,
              new AuthHttpHandler(
                new DefSyncHttpHandler()
              )
            )
          )
        )
      )
    )
  )
)
//()//todo secure?

@c4component("SnapshotMakingApp") class DefSnapshotSavers(inner: RawSnapshotSaver)
  extends SnapshotSavers(
    new SnapshotSaverImpl("snapshots",inner),
    new SnapshotSaverImpl("snapshot_txs",inner)
  )

trait SnapshotMakingAppBase extends TaskSignerApp with FileRawSnapshotLoaderApp with ConfigDataDirApp with SignedReqUtilImplApp with ConfigSimpleSignerApp
trait SnapshotPutAppBase extends SignedReqUtilImplApp with SnapshotLoaderFactoryImplApp
trait SignedReqUtilImplAppBase

trait SSEServerAppBase extends AlienProtocolApp

@c4component("SSEServerApp") class SSEServer(
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
