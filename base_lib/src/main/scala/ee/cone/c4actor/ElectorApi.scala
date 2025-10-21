package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, protocol}

import java.time.Instant

trait CurrentProcess {
  def id: SrcId
}

trait ReadyProcesses extends Product {
  def all: List[ReadyProcess]
  def enabledForCurrentRole: List[SrcId]
}

trait ReadyProcess extends Product {
  def id: SrcId
  def txId: String
  def role: String
  def startedAt: Long
  def hostname: String
  def refDescr: String
  def completionReqAt: Option[Instant]
  def complete(at: Instant): Seq[LEvent[Product]]

  // `halt` removes replica in inconsistent way, ignoring elector, so:
  // other replicas will think, that it does not exist, although it can run with tx-s yet;
  // removed replica will halt as soon as possible, even without completing cleanup hooks.
  def halt: Seq[LEvent[Product]]
}

case class EnabledTxTr(value: TxTransform)

trait ReadyProcessUtil {
  def getAll(local: AssembledContext): ReadyProcesses
  def getCurrent(local: AssembledContext): ReadyProcess
}

case class DisableTxTr(srcId: SrcId)

case class BeforeInjection(srcId: SrcId)
