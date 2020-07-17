package ee.cone.c4gate_server

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{CatchNonFatal, CheckedMap, Context, TxTransform, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4gate.LocalHttpConsumer


@c4("SeqPostHandlerApp") final class SeqPostHandlerRegistry(
  val handlers: List[SeqPostHandler]
)(
  val byURL: Map[String,SeqPostHandler] = CheckedMap(handlers.map(h=>(h.url,h)))
)

@c4multi("SeqPostHandlerApp") final case class SeqPostHandlerTx(srcId: SrcId, request: S_HttpRequest)(
  catchNonFatal: CatchNonFatal,
  reg: SeqPostHandlerRegistry,
) extends TxTransform {
  def transform(local: Context): Context = {
    val handler = reg.byURL(request.path)
    catchNonFatal {
      assert(request.method == "POST")
      handler.handle(request)(local)
    }("seq-req-handler"){ e =>
      handler.handleError(request,e)(local)
    }
  }
}

@c4assemble("SeqPostHandlerApp") class SeqPostHandlerAssembleBase(
  reg: SeqPostHandlerRegistry,
  factory: SeqPostHandlerTxFactory
) {
  type SeqSignId = SrcId

  def needConsumer(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalHttpConsumer)] =
    reg.handlers.map(h=>WithPK(LocalHttpConsumer(h.url)))

  def mapAll(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(SeqSignId,S_HttpRequest)] =
    if(reg.byURL.contains(req.path)) List(s"seqPostHandler${req.path}"->req) else Nil

  def mapTx(
    key: SrcId,
    @by[SeqSignId] requests: Values[S_HttpRequest]
  ): Values[(SrcId,TxTransform)] = {
    val req = requests.minBy(req=>(req.time,req.srcId))
    List(WithPK(factory.create(key,req)))
  }
}
