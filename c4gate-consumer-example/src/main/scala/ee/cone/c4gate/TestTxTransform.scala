package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.HttpProtocol.S_HttpRequest

@assemble("TestTxTransformApp") class TestDelayAssembleBase   {
  def joinTestHttpHandler(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(TestDelayHttpHandler(req.srcId, req)))
}

case class TestDelayHttpHandler(srcId: SrcId, req: S_HttpRequest) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"start handling $srcId")
    concurrent.blocking{
      Thread.sleep(1000)
    }
    logger.info(s"finish handling $srcId")
    TxAdd(LEvent.delete(req))(local)
  }
}
