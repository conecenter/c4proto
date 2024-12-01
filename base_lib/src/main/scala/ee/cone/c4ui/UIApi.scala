package ee.cone.c4ui

import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor.{Context, TransientLens}
import ee.cone.c4actor_branch.BranchProtocol.N_BranchResult
import ee.cone.c4actor_branch.BranchTask
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom.{Receiver, VDomLens, VDomState, VDomView}

trait View extends VDomView[Context] with Product

trait UntilPolicy {
  def wrap(view: Context=>ViewRes): Context=>ViewRes
}

case object VDomStateKey extends TransientLens[Option[VDomState]](None)
  with VDomLens[Context, Option[VDomState]]


trait ViewRestPeriodProvider {
  def get(local: Context): ViewRestPeriod
}

sealed trait ViewRestPeriod extends Product {
  def valueMillis: Long
}

case class DynamicViewRestPeriod(valueMillis: Long) extends ViewRestPeriod
case class StaticViewRestPeriod(valueMillis: Long) extends ViewRestPeriod

case object ViewRestPeriodKey extends TransientLens[Option[ViewRestPeriod]](None)

trait ViewFailed {
  def of(local: Context): Boolean
}

trait VDomUntil {
  def get(seeds: Seq[N_BranchResult]): Long
}

trait FromAlienTask extends Product {
  def branchKey: SrcId
  def branchTask: BranchTask
  def fromAlienState: U_FromAlienState
  def locationQuery: String
  def locationHash: String
}

case object CurrentBranchKey extends TransientLens[SrcId]("")

trait UpdatingReceiverFactory {
  def create(updater: Updater, action: Action): Receiver[Context]
}
trait Updater extends Product {
  type Handler = String => Context => Action => LEvents
  def receive: Handler
  def rc: UpdatingReceiverFactory
  def rc(action: Action): Receiver[Context] = rc.create(this, action)
}
trait Action extends Product