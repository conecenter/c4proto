
package ee.cone.c4ui

import ee.cone.c4actor.{BranchHandler, BranchProtocol, BranchTask, SendToAlienKey}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

import scala.Function.chain

/*
trait UIApp extends AlienExchangeApp with BranchApp with VDomApp with InitLocalsApp with AssemblesApp {
  type VDomStateContainer = World
  lazy val vDomStateKey: VDomLens[World,Option[VDomState]] = VDomStateKey
  //lazy val relocateKey: VDomLens[World, String] = RelocateKey
  private lazy val sseUI = new UIInit(tags,vDomHandlerFactory,branchOperations)
  override def assembles: List[Assemble] = new VDomAssemble :: super.assembles
  override def initLocals: List[InitLocal] = sseUI :: DefaultUntilPolicyInit :: super.initLocals
}


@assemble class VDomAssemble extends Assemble {
  def joinBranchHandler(
    key: SrcId,
    tasks: Values[BranchTask],
    views: Values[View]
  ): Values[(SrcId,BranchHandler)] =
    for(task ← tasks; view ← views) yield task.branchKey →
      VDomBranchHandler(task.branchKey, VDomBranchSender(task),view)
}
case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[World], view: VDomView[World]) extends BranchHandler {
  def vHandler: World ⇒ VDomHandler[World] =
    local ⇒ CreateVDomHandlerKey.of(local)(sender,view)
  def exchange: (String ⇒ String) ⇒ World ⇒ World =
    message ⇒ local ⇒ {
      //println(s"act ${message("X-r-action")}")
      vHandler(local).exchange(message)(local)
    }
  def seeds: World ⇒ List[BranchResult] =
    local ⇒ vHandler(local).seeds(local).collect{ case r: BranchResult ⇒ r }
}
case class TestSSEHandler(branchKey: SrcId, task: BranchTask) extends BranchHandler {
  def exchange: (String ⇒ String) ⇒ World ⇒ World = message ⇒ local ⇒ {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) local
    else {
      val messages = task.sessionKeys(local).toSeq.map { sessionKey ⇒
        ToAlienWrite(s"${UUID.randomUUID}",sessionKey,"show",s"$seconds",0)
      }
      println(s"TestSSEHandler ${task.sessionKeys(local)}")
      TestTimerKey.set(seconds).andThen(add(messages.flatMap(update)))(local)
    }
  }
  def seeds: World ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}
*/

case class CanvasState(value: String="", until: Long=0L, sessionKeys: Set[String]=Set.empty)

case object CanvasStateKey extends WorldKey[CanvasState](CanvasState())

case class CanvasBranchHandler(branchKey: SrcId, task: BranchTask)(view: World⇒CanvasState) extends BranchHandler {
  private type Handler = (String ⇒ String) ⇒ World ⇒ World
  def exchange: Handler =
    m ⇒ chain(Seq(dispatch,toAlien,ackChange).map(_(m)))
  private def reset = CanvasStateKey.set(CanvasState())
  private def dispatch: Handler = message ⇒ local ⇒ message("X-r-canvas-eventType") match {
    case "" ⇒ local
    case "canvasResize" ⇒

      ???
    case t ⇒
      ???
      reset(local)
  }
  private def toAlien: Handler = message ⇒ local ⇒ {
    val cState = CanvasStateKey.of(local)
    val newSessionKeys = task.sessionKeys(local)
    val(keepTo,freshTo) = newSessionKeys.partition(cState.sessionKeys)
    if(newSessionKeys.isEmpty) reset(local)
    else if(
      cState.value.nonEmpty &&
      cState.until > System.currentTimeMillis &&
      freshTo.isEmpty
    ) local
    else {
      val nState = view(local)
      val to = if(cState.value==nState.value) freshTo else newSessionKeys
      val message = s"$branchKey ${nState.value}"
      val send = SendToAlienKey.of(local)(_,"showCanvasData",message)
      CanvasStateKey.set(nState.copy(sessionKeys = newSessionKeys))
        .andThen(chain(to.map(send).toSeq))(local)
    }
  }
  private def ackChange: Handler = message ⇒ local ⇒ if(message("X-r-canvas-eventType") == "canvasResize") {
    val sessionKey = message("X-r-session")
    val branchKey = message("X-r-branch")
    val size = message("X-r-canvas-canvasFontSize")
    val width = message("X-r-canvas-canvasWidth")
    SendToAlienKey.of(local)(sessionKey,"ackCanvasResize",s"$branchKey $size $width")(local)
  } else local

  def seeds: World ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}

