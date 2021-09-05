
package ee.cone.c4gate_server

import java.time.Instant
import okio.ByteString
import ee.cone.c4di.c4multi
import ee.cone.c4assemble.c4assemble
import ee.cone.c4assemble.Types._
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4gate._
import ee.cone.c4gate.HttpProtocol.N_Header

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
    val port = config.get("C4SSE_PORT")
    val headers = List(N_Header("x-r-redirect-inner",s"http://127.0.0.1:$port"))
    val publication = ByPathHttpPublication("/sse", headers, ByteString.EMPTY)
    val events = publisher.publish("Proxy",List(publication))(local)
    txAdd.add(events).andThen(SleepUntilKey.set(Instant.now.plusSeconds(60)))(local)
  }
}