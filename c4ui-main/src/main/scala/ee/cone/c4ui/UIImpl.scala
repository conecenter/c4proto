package ee.cone.c4ui

import ee.cone.c4actor.BranchProtocol.S_BranchResult
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4proto.c4component
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom._
import okio.ByteString

@c4component("UIApp") class UIInit(vDomHandlerFactory: VDomHandlerFactory) extends ToInject {
  def toInject: List[Injectable] = List(
    CreateVDomHandlerKey.set((sender,view) =>
      vDomHandlerFactory.create(sender,view,VDomUntilImpl,VDomStateKey)
    )
  ).flatten
}

//case object RelocateKey extends WorldKey[String]("")
//  with VDomLens[World, String]

@assemble("UIApp") class VDomAssembleBase {
  def joinBranchHandler(
    key: SrcId,
    task: Each[BranchTask],
    view: Each[View]
  ): Values[(SrcId,BranchHandler)] =
    List(WithPK(VDomBranchHandler(task.branchKey, VDomBranchSender(task),view)))
}

case class VDomBranchSender(pass: BranchTask) extends VDomSender[Context] {
  def branchKey: String = pass.branchKey
  def sending: Context => (Send,Send) = pass.sending
}

case object CreateVDomHandlerKey extends SharedComponentKey[(VDomSender[Context],VDomView[Context])=>VDomHandler[Context]]

case class VDomMessageImpl(message: BranchMessage) extends VDomMessage {
  override def header: String => String = message.header
  override def body: ByteString = message.body
}

case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[Context], view: VDomView[Context]) extends BranchHandler {
  def vHandler: Context => VDomHandler[Context] =
    local => CreateVDomHandlerKey.of(local)(sender,view)
  def exchange: BranchMessage => Context => Context =
    message => local => {
      val vDomMessage = VDomMessageImpl(message)
      //println(s"act ${message("x-r-action")}")
      val handlePath = vDomMessage.header("x-r-vdom-path")
      (CurrentPathKey.set(handlePath) andThen
        vHandler(local).receive(vDomMessage))(local)
    }
  def seeds: Context => List[S_BranchResult] =
    local => vHandler(local).seeds(local).collect{
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

@c4component("UIApp") object DefaultUntilPolicy extends UntilPolicy {
  def wrap(view: Context=>ViewRes): Context=>ViewRes = local => {
    val startTime = System.currentTimeMillis
    val res = view(local)
    val endTime = System.currentTimeMillis
    val until = endTime+Math.max((endTime-startTime)*10, 500)
    UntilPair("until",until) :: res
  }
}