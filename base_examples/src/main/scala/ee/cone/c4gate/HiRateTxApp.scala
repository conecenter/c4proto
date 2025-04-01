package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.c4multi
import ee.cone.c4proto.ToByteString

@c4("HiRateTxApp") final case class HiRateTx(srcId: SrcId = "HiRateTx")(
  publisher: Publisher,
) extends SingleTxTr with LazyLogging {
  def transform(local: Context): TxEvents = {
    val timeStr = System.currentTimeMillis.toString
    logger.info(s"start handling $timeStr")
    val bytes = ToByteString(timeStr)
    publisher.publish(ByPathHttpPublication("/time",Nil,bytes),_+1000*60)
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
    txAdd.add(delete(post))(local)
  }
}*/
