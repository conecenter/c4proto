package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, protocol}

trait ReadyProcesses extends Product {
  def ids: List[SrcId]
  def isMaster: Boolean
  def currentId: SrcId
  def sameVerIds: List[SrcId]
  def processes: List[ReadyProcess]
}

trait ReadyProcess extends Product {
  def id: SrcId
  def startedAt: Long
  def hostname: String
  def image: String

  // `halt` removes replica in inconsistent way, ignoring elector, so:
  // other replicas will think, that it does not exist, although it can run with tx-s yet;
  // removed replica will halt as soon as possible, even without completing cleanup hooks.
  def halt: Seq[LEvent[Product]]
}

case class EnabledTxTr(value: TxTransform)

trait EnableScaling
