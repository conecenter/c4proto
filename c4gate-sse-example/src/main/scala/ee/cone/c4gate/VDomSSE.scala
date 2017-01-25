package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, WorldKey, assemble}
import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.{JsonToStringImpl, VDomHandlerImpl, WasNoValueImpl}
import ee.cone.c4vdom_mix.VDomApp

import Function.chain

trait VDomSSEApp extends AlienExchangeApp with BranchApp with VDomApp with InitLocalsApp with AssemblesApp {
  def tags: Tags

  type VDomStateContainer = World
  lazy val vDomStateKey: VDomLens[World,Option[VDomState]] = VDomStateKey
  lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val testTags = new TestTags[World](childPairFactory, tagJsonUtils)
  private lazy val sseUI = new InitLocal {
    def initLocal: World ⇒ World = chain(Seq(
      TagsKey.set(Option(tags)),
      TestTagsKey.set(Option(testTags)),
      CreateVDomHandlerKey.set((sender,view) ⇒
        VDomHandlerImpl(sender,view)(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory,tags,vDomStateKey,relocateKey)
      ),
      BranchOperationsKey.set(Option(branchOperations))
    ))
  }
  override def assembles: List[Assemble] = new VDomAssemble :: super.assembles
  override def initLocals: List[InitLocal] = sseUI :: super.initLocals
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
  def send: (String,String,String) ⇒ World ⇒ World =
    (sessionKey,event,data) ⇒ local ⇒ SendToAlienKey.of(local)(sessionKey,event,data)(local)
}

case object CreateVDomHandlerKey extends WorldKey[(VDomSender[World],VDomView[World])⇒VDomHandler[World]]((_,_)⇒throw new Exception)

case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[World], view: VDomView[World]) extends BranchHandler {
  def vHandler: World ⇒ VDomHandler[World] = local ⇒ CreateVDomHandlerKey.of(local)(sender,view)
  def exchange: ((String) ⇒ String) ⇒ (World) ⇒ World = message ⇒ local ⇒ vHandler(local).exchange(message)(local)
  def seeds: World ⇒ List[BranchResult] = local ⇒ vHandler(local).seeds(local).collect{ case r: BranchResult ⇒ r }
}
