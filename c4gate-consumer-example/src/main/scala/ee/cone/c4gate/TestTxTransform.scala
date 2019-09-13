package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4gate.HttpProtocolBase.{N_Header, S_HttpResponse}
import ee.cone.c4proto.{Protocol, ToByteString}

class TestSerialApp extends TestTxTransformApp with SerialObserversApp
class TestParallelApp extends TestTxTransformApp with ParallelObserversApp

abstract class TestTxTransformApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
  with TreeIndexValueMergerFactoryApp
  with BasicLoggingApp
{
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def assembles: List[Assemble] = new TestDelayAssemble :: super.assembles
}

@assemble class TestDelayAssembleBase   {
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
