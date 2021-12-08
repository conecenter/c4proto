package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

trait ReadyProcesses extends Product {
  def ids: List[SrcId]
  def isMaster: Boolean
  def currentId: SrcId
}

case class EnabledTxTr(value: TxTransform)

trait EnableScaling
