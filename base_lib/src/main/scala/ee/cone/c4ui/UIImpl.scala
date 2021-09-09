package ee.cone.c4ui

import ee.cone.c4actor_branch.BranchProtocol.S_BranchResult
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom._
import okio.ByteString

//case object RelocateKey extends WorldKey[String]("")
//  with VDomLens[World, String]

@c4assemble("UICompApp") class VDomAssembleBase(
  factory: VDomBranchHandlerFactory
){
  def joinBranchHandler(
    key: SrcId,
    task: Each[BranchTask],
    view: Each[View]
  ): Values[(SrcId,BranchHandler)] =
    List(WithPK(factory.create(task.branchKey, VDomBranchSender(task),view)))
}

case class VDomBranchSender(pass: BranchTask) extends VDomSender[Context] {
  def branchKey: String = pass.branchKey
  def sending: Context => (Send,Send) = pass.sending
}

case class VDomMessageImpl(message: BranchMessage) extends VDomMessage {
  override def header: String => String = message.header
  override def body: ByteString = message.body
}

@c4multi("UICompApp") final case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[Context], view: VDomView[Context])(
  vDomHandlerFactory: VDomHandlerFactory,
) extends BranchHandler {
  def vHandler: VDomHandler[Context] =
      vDomHandlerFactory.create(sender,view,VDomUntilImpl,VDomStateKey)
  def exchange: BranchMessage => Context => Context =
    message => local => {
      val vDomMessage = VDomMessageImpl(message)
      //println(s"act ${message("x-r-action")}")
      val handlePath = vDomMessage.header("x-r-vdom-path")
      (CurrentPathKey.set(handlePath) andThen
        vHandler.receive(vDomMessage))(local)
    }
  def seeds: Context => List[S_BranchResult] =
    local => vHandler.seeds(local).collect{
      case (k: String, r: S_BranchResult) => r.copy(position=k)
    }
}

////

object VDomUntilImpl extends VDomUntil {
  def get(pairs: ViewRes): (Long, ViewRes) =
    (pairs.collect{ case u: UntilPair => u.until } match {
      case l if l.isEmpty => 0L
      case l => l.min
    }, pairs.filterNot(_.isInstanceOf[UntilPair]))
}

case class UntilPair(key: String, until: Long) extends ChildPair[OfDiv]

@c4("UICompApp") final class DefaultUntilPolicy(
  viewRestPeriodProvider: ViewRestPeriodProvider
) extends UntilPolicy {
  def wrap(view: Context=>ViewRes): Context=>ViewRes = local => {
    val restPeriod = viewRestPeriodProvider.get(local)
    val res = view(ViewRestPeriodKey.set(restPeriod)(local))
    UntilPair("until",System.currentTimeMillis+restPeriod) :: res
  }
}

@c4("UICompApp") final class DynamicViewRestPeriodProvider() extends ViewRestPeriodProvider {
  def get(local: Context): Long = {
    val state = VDomStateKey.of(local).get
    val age = System.currentTimeMillis() - state.startedAtMillis
    val allowedMaking = age / 10
    Math.max(state.wasMakingViewMillis - allowedMaking, 500)
  }
}
