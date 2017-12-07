package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.HttpPost

class TestSerialApp extends TestTxTransformApp with `The SerialObserverProvider`
class TestParallelApp extends TestTxTransformApp with `The ParallelObserverProvider`

abstract class TestTxTransformApp extends ServerApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaProducerApp with KafkaConsumerApp
  with `The NoAssembleProfiler`
  with FileRawSnapshotApp
  with TreeIndexValueMergerFactoryApp
  with `The HttpProtocol` with `The TestDelayAssemble`

@assemble class TestDelayAssemble {
  def joinTestHttpPostHandler(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    for(post ← posts) yield WithPK(TestDelayHttpPostHandler(post))
}

case class TestDelayHttpPostHandler(post: HttpPost) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"start handling ${post.srcId}")
    concurrent.blocking{
      Thread.sleep(1000)
    }
    logger.info(s"finish handling ${post.srcId}")
    TxAdd(delete[Product](post))(local)
  }
}
