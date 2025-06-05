package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{SrcId, TxEvents}
import ee.cone.c4di.{c4, provide}

@c4("ScalingTestApp") final class FailOverTestEnable
  extends EnableSimpleScaling(classOf[FailOverTestTx])

@c4("ScalingTestApp") final class FailOverTestProvider {
  @provide def toTx: Seq[SingleTxTr] = Seq(FailOverTestTx("FailOverTest-0"), FailOverTestTx("FailOverTest-1"))
}

case class FailOverTestTx(srcId: SrcId) extends SingleTxTr with LazyLogging {
  def transform(local: Context): TxEvents = {
    logger.info(s"Tx up $srcId")
    Nil
  }
}

