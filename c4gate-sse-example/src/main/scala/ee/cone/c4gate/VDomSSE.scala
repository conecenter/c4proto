package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{AssemblesApp, InitLocal, InitLocalsApp, TxTransform}
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, Single, WorldKey, assemble}
import ee.cone.c4gate.BranchProtocol.BranchResult
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.{JsonToStringImpl, VDomHandlerImpl, WasNoValueImpl}
import ee.cone.c4vdom_mix.VDomApp

trait VDomSSEApp extends BranchApp with VDomApp with InitLocalsApp with AssemblesApp{
  def tags: Tags

  type VDomStateContainer = World
  lazy val vDomStateKey: VDomLens[World,Option[VDomState]] = VDomStateKey
  lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val testTags = new TestTags[World](childPairFactory, tagJsonUtils)
  private lazy val sseUI = new InitLocal {
    def initLocal: World ⇒ World =
      TagsKey.set(Option(tags))
      .andThen(TestTagsKey.set(Option(testTags)))
  }
  lazy val createVDomBranchHandler: (BranchTask,View)⇒BranchTask = (task,view) ⇒
    task.withHandler(VDomBranchHandler(VDomHandlerImpl(VDomBranchSender(task.sender),view)(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory,vDomStateKey,relocateKey)))

  override def assembles: List[Assemble] = new VDomAssemble(createVDomBranchHandler) :: super.assembles
  override def initLocals: List[InitLocal] = sseUI :: super.initLocals
}

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with VDomLens[World, Option[VDomState]]
case object RelocateKey extends WorldKey[String]("")
  with VDomLens[World, String]


case object TagsKey extends WorldKey[Option[Tags]](None)
case object TestTagsKey extends WorldKey[Option[TestTags[World]]](None)

////

trait View extends VDomView[World] {
  def view: World ⇒ List[ChildPair[_]]
}

@assemble class VDomAssemble(createVDomBranchHandler: (BranchTask,View)⇒BranchTask) extends Assemble {
  def join(
    key: SrcId,
    tasks: Values[BranchTask],
    views: Values[View]
  ): Values[(SrcId,TxTransform)] =
    for(task ← tasks) yield key → createVDomBranchHandler(task,Single(views))
}

case class VDomBranchSender(pass: BranchSender) extends VDomSender[World] {
  def branchKey: String = pass.branchKey
  def sessionKeys: World ⇒ Set[String] = pass.sessionKeys
  def send: (String, String, String) ⇒ World ⇒ World = pass.send
}

case class VDomBranchHandler(pass: VDomHandler[World]) extends BranchHandler {
  def exchange: ((String) ⇒ String) ⇒ (World) ⇒ World = pass.exchange
  def seeds: World ⇒ List[BranchResult] = pass.seeds.andThen(_.collect{ case r: BranchResult ⇒ r })
}

/*
case class VDomView(branchKey: SrcId, sender: BranchSender, views:
Values[View]) extends BranchHandler {
  def exchange: (String⇒String) ⇒ World ⇒ World = post ⇒ local ⇒
    CurrentVDomKey.of(local).get.exchange(new Exchange[World] {
      def get: String ⇒ String = post
      def send: (String, String, String) ⇒ World ⇒ World = sender.send
      def sessionKeys: World ⇒ Set[String] = sender.sessionKeys
    })(local)
  def seeds: World ⇒ List[BranchResult] = local ⇒
    CurrentVDomKey.of(local).get
      .seeds(local)
}*/