package ee.cone.c4gate

import java.net.URL
import java.util.UUID

import ee.cone.c4actor.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{add, delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, Single, WorldKey, assemble}
import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4gate.AlienProtocol.{FromAlienState, ToAlienWrite}
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4proto.Protocol
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.{JsonToStringImpl, VDomHandlerImpl, WasNoValueImpl}
import ee.cone.c4vdom_mix.VDomApp

trait VDomSSEApp extends BranchApp with VDomApp with InitLocalsApp with AssemblesApp with ProtocolsApp {
  def tags: Tags

  type VDomStateContainer = World
  lazy val vDomStateKey: VDomLens[World,Option[VDomState]] = VDomStateKey
  lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val testTags = new TestTags[World](childPairFactory, tagJsonUtils)
  private lazy val sseUI = new InitLocal {
    def initLocal: World ⇒ World =
      TagsKey.set(Option(tags))
      .andThen(TestTagsKey.set(Option(testTags)))
      .andThen(CreateVDomHandlerKey.set((sender,view) ⇒
        VDomHandlerImpl(sender,view)(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory,vDomStateKey,relocateKey)
      ))
      .andThen(BranchOperationsKey.set(Option(branchOperations)))
  }
  override def assembles: List[Assemble] = new VDomAssemble :: new MessageFromAlienAssemble :: super.assembles
  override def initLocals: List[InitLocal] = sseUI :: super.initLocals
  override def protocols: List[Protocol] = HttpProtocol :: AlienProtocol :: super.protocols
}

case object BranchOperationsKey extends  WorldKey[Option[BranchOperations]](None)

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with VDomLens[World, Option[VDomState]]
case object RelocateKey extends WorldKey[String]("")
  with VDomLens[World, String]


case object TagsKey extends WorldKey[Option[Tags]](None)
case object TestTagsKey extends WorldKey[Option[TestTags[World]]](None)

////

trait View extends VDomView[World]

@assemble class VDomAssemble extends Assemble {
  def joinBranchHandler(
    key: SrcId,
    tasks: Values[BranchTask],
    views: Values[View]
  ): Values[(SrcId,BranchHandler)] =
    for(task ← tasks; view ← views) yield task.branchKey →
      VDomBranchHandler(task.branchKey, VDomBranchSender(task),view)
}

case object ToAlienPriorityKey extends WorldKey[java.lang.Long](0L)

case class VDomBranchSender(pass: BranchTask) extends VDomSender[World] {
  def branchKey: String = pass.branchKey
  def sessionKeys: World ⇒ Set[String] = pass.sessionKeys
  def send: (String,String,String) ⇒ World ⇒ World = (sessionKey,event,data) ⇒ local ⇒
    add(update(ToAlienWrite(UUID.randomUUID.toString,sessionKey,event,data,ToAlienPriorityKey.of(local))))
      .andThen(ToAlienPriorityKey.modify(_+1))(local)
}

case object CreateVDomHandlerKey extends WorldKey[(VDomSender[World],VDomView[World])⇒VDomHandler[World]]((_,_)⇒throw new Exception)

case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[World], view: VDomView[World]) extends BranchHandler {
  def vHandler: World ⇒ VDomHandler[World] = local ⇒ CreateVDomHandlerKey.of(local)(sender,view)
  def exchange: ((String) ⇒ String) ⇒ (World) ⇒ World = message ⇒ local ⇒ vHandler(local).exchange(message)(local)
  def seeds: World ⇒ List[BranchResult] = local ⇒ vHandler(local).seeds(local).collect{ case r: BranchResult ⇒ r }
}
