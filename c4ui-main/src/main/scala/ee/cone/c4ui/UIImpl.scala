package ee.cone.c4ui

import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, WorldKey, assemble}
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom._
import okio.ByteString

import scala.Function.chain

class UIInit(
  tags: Tags,
  styles: TagStyles,
  vDomHandlerFactory: VDomHandlerFactory,
  branchOperations: BranchOperations
) extends InitLocal {
  def initLocal: World ⇒ World = chain(List(
    TagsKey.set(Option(tags)),
    TagStylesKey.set(Option(styles)),
    CreateVDomHandlerKey.set((sender,view) ⇒
      vDomHandlerFactory.create(sender,view,VDomUntilImpl,VDomStateKey)
    ),
    BranchOperationsKey.set(Option(branchOperations))
  ))
}

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with VDomLens[World, Option[VDomState]]
//case object RelocateKey extends WorldKey[String]("")
//  with VDomLens[World, String]

@assemble class VDomAssemble extends Assemble {
  def joinBranchHandler(
    key: SrcId,
    tasks: Values[BranchTask],
    views: Values[View]
  ): Values[(SrcId,BranchHandler)] =
    for(task ← tasks; view ← views) yield task.branchKey →
      VDomBranchHandler(task.branchKey, VDomBranchSender(task),view)
}

case class VDomBranchSender(pass: BranchTask) extends VDomSender[World] {
  def branchKey: String = pass.branchKey
  def sending: World ⇒ (Send,Send) = pass.sending
}

case object CreateVDomHandlerKey extends WorldKey[(VDomSender[World],VDomView[World])⇒VDomHandler[World]]((_,_)⇒throw new Exception)

case class VDomMessageImpl(message: BranchMessage) extends VDomMessage {
  override def header: String ⇒ String = message.header
  override def body: ByteString = message.body
}

case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[World], view: VDomView[World]) extends BranchHandler {
  def vHandler: World ⇒ VDomHandler[World] =
    local ⇒ CreateVDomHandlerKey.of(local)(sender,view)
  def exchange: BranchMessage ⇒ World ⇒ World =
    message ⇒ local ⇒ {
      //println(s"act ${message("X-r-action")}")
      vHandler(local).receive(VDomMessageImpl(message))(local)
    }
  def seeds: World ⇒ List[BranchResult] =
    local ⇒ vHandler(local).seeds(local).collect{
      case (k: String, r: BranchResult) ⇒ r.copy(position=k)
    }
}

////

object VDomUntilImpl extends VDomUntil {
  def get(pairs: ViewRes): (Long, ViewRes) =
    (pairs.collect{ case u: UntilPair ⇒ u.until } match {
      case l if l.isEmpty ⇒ 0L
      case l ⇒ l.min
    }, pairs.filterNot(_.isInstanceOf[UntilPair]))
}

case class UntilPair(key: String, until: Long) extends ChildPair[OfDiv]

object DefaultUntilPolicyInit extends InitLocal {
  def initLocal: World ⇒ World = UntilPolicyKey.set{ view ⇒
    val startTime = System.currentTimeMillis
    val res = view()
    val endTime = System.currentTimeMillis
    val until = endTime+Math.max((endTime-startTime)*10, 500)
    UntilPair("until",until) :: res
  }
}