package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assembled

case class SyncTxTask[Item<:Product](
  srcId: SrcId,
  from: Option[Item],
  to: Option[Item],
  events: List[LEvent[Item]]
)

object SyncTx {
  type NeedSrcId = SrcId
}

trait SyncTxFactory {
  def apply[Item<:Product](classOfItem: Class[Item])(
    filter: Item⇒Boolean,
    group: Item⇒SrcId,
    txTransform: Option[(SrcId,List[SyncTxTask[Item]])⇒TxTransform]
  ): Assembled
}