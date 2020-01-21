package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, c4assemble}
import ee.cone.c4gate.HttpProtocol.S_HttpPublication
import ee.cone.c4proto.ToByteString

@c4assemble("HiRateTxApp") class HiRateAssembleBase {
  def joinPosts(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId, TxTransform)] = List(WithPK(HiRateTx("HiRateTx")))
}

case class HiRateTx(srcId: SrcId) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val timeStr = System.currentTimeMillis.toString
    logger.info(s"start handling $timeStr")
    val bytes = ToByteString(timeStr)
    TxAdd(LEvent.update(S_HttpPublication("/time",Nil,bytes,None)))(local)
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
