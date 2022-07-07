package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4

@c4("ScalingTestApp") final class FailOverTestEnable
  extends EnableSimpleScaling(classOf[FailOverTestTx])

@c4assemble("ScalingTestApp") class FailOverTestAssembleBase{
  def toTx(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
  ): Values[(SrcId, TxTransform)] =
    List(
      WithPK(FailOverTestTx("FailOverTest-0")),
      WithPK(FailOverTestTx("FailOverTest-1")),
    )
}

case class FailOverTestTx(srcId: SrcId) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"Tx up $srcId")
    local
  }
}

