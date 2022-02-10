
package ee.cone.c4gate_server

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import okio.ByteString
import ee.cone.c4di._
import ee.cone.c4assemble.c4assemble
import ee.cone.c4assemble.Types._
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4gate._
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate_server.RHttpTypes.{RHttpHandler, RHttpHandlerCreate}
import ee.cone.c4proto.{Id, protocol}
import ee.cone.c4gate_server.HttpProxyProtocol._

@c4assemble("SSEServerApp") class ProxyAssembleBase(factory: ProxyTxFactory) {
  def join(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId, TxTransform)] = List(WithPK(factory.create("ProxyTx")))
}

@c4multi("SSEServerApp") final case class ProxyTx(srcId: SrcId)(
  config: Config,
  publisher: Publisher,
  txAdd: LTxAdd,
) extends TxTransform {
  def transform(local: Context): Context = {
    val ip = config.get("C4POD_IP")
    val port = config.get("C4SSE_PORT")
    val to = s"http://$ip:$port"
    val headers = List(N_Header("x-r-redirect-inner",to))
    val publication = ByPathHttpPublication("/sse", headers, ByteString.EMPTY)
    val events = publisher.publish("Proxy",List(publication))(local)
    txAdd.add(events).andThen(SleepUntilKey.set(Instant.now.plusSeconds(60)))(local)
  }
}

////

@protocol("SSEServerApp") object HttpProxyProtocol {
  @Id(0x00B3) case class S_HttpProxy(
    @Id(0x0011) srcId: SrcId,
    @Id(0x00B3) to: String,
  )
}

@c4("SSEServerApp") final class HttpProxyConfig(config: Config)(
  val to: String = {
    val ip = config.get("C4POD_IP")
    val port = config.get("C4HTTP_PORT")
    s"http://$ip:$port"
  }
)

@c4assemble("SSEServerApp") class HttpProxyAssembleBase(
  config: Config,
  factory: HttpProxyTxFactory,
  httpProxyConfig: HttpProxyConfig,
){
  def join(
    key: SrcId,
    firstborn: Each[S_Firstborn],
    ips: Values[S_HttpProxy],
  ): Values[(SrcId, TxTransform)] = {
    val to = httpProxyConfig.to
    if(ips.exists(_.to==to)) Nil
    else List(WithPK(factory.create("HttpProxyTx",S_HttpProxy(key,to))))
  }
}

@c4multi("SSEServerApp") final case class HttpProxyTx[P<:Product](srcId: SrcId, ip: S_HttpProxy)(
  txAdd: LTxAdd,
) extends TxTransform {
  def transform(local: Context): Context = txAdd.add(LEvent.update(ip))(local)
}

@c4("SSEServerApp") final class PongProxyHandler(
  sseConfig: SSEConfig,
  httpResponseFactory: RHttpResponseFactory,
  getS_HttpProxy: GetByPK[S_HttpProxy],
  actorName: ActorName,
  httpProxyConfig: HttpProxyConfig,
  config: Config,
)(
  val port: String = config.get("C4HTTP_PORT"),
) extends LazyLogging {
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if(request.method == "POST" && request.path == sseConfig.pongURL){
      getS_HttpProxy.ofA(local).get(actorName.value) match {
        case Some(pr) if pr.to!=httpProxyConfig.to =>
          val to = s"orig:${pr.to}${sseConfig.pongURL}"
          logger.debug(to)
          val headers = List(N_Header("x-r-redirect-inner",to))
          httpResponseFactory.directResponse(request,_.copy(headers=headers))
        case _ => next(request,local)
      }
    } else next(request,local)
}
