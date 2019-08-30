package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assemble

case class SyncTxTask[D_Item<:Product](
  srcId: SrcId,
  from: Option[D_Item],
  to: Option[D_Item],
  events: List[LEvent[D_Item]]
)

object SyncTx {
  type NeedSrcId = SrcId
}

trait SyncTxFactory {
  def create[D_Item<:Product](
    classOfItem: Class[D_Item],
    filter: D_Item⇒Boolean,
    group: D_Item⇒SrcId,
    txTransform: (SrcId,List[SyncTxTask[D_Item]])⇒TxTransform
  ): Assemble
}