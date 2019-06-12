package ee.cone.c4gate

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.QProtocolBase.Firstborn
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4gate.HttpProtocolBase.HttpPublication
import ee.cone.c4proto.{Protocol, ToByteString}



class HiRateTxApp extends ServerApp with ParallelObserversApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
{
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def assembles: List[Assemble] = new HiRateAssemble :: super.assembles
}

@assemble class HiRateAssembleBase {
  def joinPosts(
    key: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] = List(WithPK(HiRateTx("HiRateTx")))
}

case class HiRateTx(srcId: SrcId) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val timeStr = System.currentTimeMillis.toString
    logger.info(s"start handling $timeStr")
    val bytes = ToByteString(timeStr)
    TxAdd(LEvent.update(HttpPublication("/time",Nil,bytes,None)))(local)
  }
}

/*
@assemble class HiRateAssemble {
  def joinPosts(
    key: SrcId,
    post: Each[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(TestDelayHttpPostHandler(post.srcId, post)))
}

case class HiRateTx(srcId: SrcId, post: HttpPost) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"start handling $srcId")
    concurrent.blocking{
      Thread.sleep(1000)
    }
    logger.info(s"finish handling $srcId")
    TxAdd(delete(post))(local)
  }
}*/
