package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assemble

case class SyncTxTask[Item<:Product](
  srcId: SrcId,
  from: Option[Item],
  to: Option[Item],
  events: List[LEvent[Product]]
)

object SyncTx {
  type NeedSrcId = SrcId
}

trait SyncTxFactory {
  def create[Item<:Product](
    classOfItem: Class[Item],
    filter: Item⇒Boolean,
    group: Item⇒SrcId,
    txTransform: (SrcId,List[SyncTxTask[Item]])⇒TxTransform
  ): Assemble
}