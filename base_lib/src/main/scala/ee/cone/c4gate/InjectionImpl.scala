package ee.cone.c4gate

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4multi
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest, S_HttpResponse}
import okio.ByteString

@c4assemble("InjectionApp") class InjectionAssembleBase(
  injectionTxFactory: InjectionTxFactory,
  val url: String = "/injection",
){
  def needConsumer(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(url)))

  def mapTx(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(SrcId,TxTransform)] =
    if(req.path == url) List(WithPK(injectionTxFactory.create(req))) else Nil
}

@c4multi("InjectionApp") final case class InjectionTx(req: S_HttpRequest)(
  txAdd: LTxAdd, signatureChecker: SimpleSigner, catchNonFatal: CatchNonFatal, indentedParser: AbstractIndentedParser,
  rawTxAdd: RawTxAdd,
) extends TxTransform {
  private def header(headers: List[N_Header], key: String): Option[String] = headers.find(_.key == key).map(_.value)
  private def respond(code: Int): Seq[LEvent[Product]] =
    LEvent.update(S_HttpResponse(req.srcId,code,Nil,ByteString.EMPTY,System.currentTimeMillis)) ++ LEvent.delete(req)
  def transform(local: Context): Context = catchNonFatal{
    assert(req.method == "POST")
    val Some(Seq("/injection",dataHash)) = signatureChecker.retrieve(check=true)(header(req.headers,"x-r-signed"))
    assert(dataHash == req.body.sha512().hex())
    //
    val nLocal = rawTxAdd.add(indentedParser.toUpdates(req.body.utf8()))(local)
    txAdd.add(respond(200))(nLocal)
  }("injection"){ e =>
    txAdd.add(respond(500))(local)
  }
}
