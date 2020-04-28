package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, c4assemble}
import ee.cone.c4di.c4multi
import ee.cone.c4gate.HttpProtocol.S_HttpRequest

@c4assemble("TestTxTransformApp") class TestDelayAssembleBase(
  factory: TestDelayHttpHandlerFactory,
){
  def joinTestHttpHandler(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(factory.create(req.srcId, req)))
}

@c4multi("TestTxTransformApp") final case class TestDelayHttpHandler(srcId: SrcId, req: S_HttpRequest)(
  txAdd: LTxAdd,
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"start handling $srcId")
    concurrent.blocking{
      Thread.sleep(1000)
    }
    logger.info(s"finish handling $srcId")
    txAdd.add(LEvent.delete(req))(local)
  }
}
