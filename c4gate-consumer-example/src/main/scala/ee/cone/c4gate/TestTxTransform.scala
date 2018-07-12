package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4proto.Protocol

class TestSerialApp extends TestTxTransformApp with SerialObserversApp
class TestParallelApp extends TestTxTransformApp with ParallelObserversApp

abstract class TestTxTransformApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
  with TreeIndexValueMergerFactoryApp
{
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def assembles: List[Assemble] = new TestDelayAssemble :: super.assembles
}

@assemble class TestDelayAssemble extends Assemble {
  def joinTestHttpPostHandler(
    key: SrcId,
    post: Each[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(TestDelayHttpPostHandler(post.srcId, post)))
}

case class TestDelayHttpPostHandler(srcId: SrcId, post: HttpPost) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"start handling $srcId")
    concurrent.blocking{
      Thread.sleep(1000)
    }
    logger.info(s"finish handling $srcId")
    TxAdd(delete[Product](post))(local)
  }
}
