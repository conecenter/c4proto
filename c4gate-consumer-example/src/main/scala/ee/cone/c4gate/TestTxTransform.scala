package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4proto.Protocol

class TestSerialApp extends TestTxTransformApp with SerialObserversApp
class TestParallelApp extends TestTxTransformApp with ParallelObserversApp

abstract class TestTxTransformApp extends ServerApp
  with `The EnvConfigImpl` with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with `The NoAssembleProfiler`
  with FileRawSnapshotApp
  with TreeIndexValueMergerFactoryApp
  with `The HttpProtocol` with `The TestDelayAssemble`

@c4component @listed @assemble case class TestDelayAssemble() extends Assemble {
  def joinTestHttpPostHandler(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    posts.map(post ⇒ post.srcId → TestDelayHttpPostHandler(post.srcId, post))
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
