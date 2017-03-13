
package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, Single, WorldKey, assemble}

import scala.Function.chain

@assemble class CanvasAssemble extends Assemble {
  def joinBranchHandler(
    key: SrcId,
    tasks: Values[BranchTask],
    canvasHandlers: Values[CanvasHandler]
  ): Values[(SrcId,BranchHandler)] =
    for(task ← tasks; canvasHandler ← Single.list(canvasHandlers))
      yield task.branchKey → CanvasBranchHandler(task.branchKey, task, canvasHandler)
}

case object CanvasSizesKey extends WorldKey[Option[CanvasSizes]](None)
case class CanvasSizes(canvasFontSize: BigDecimal, canvasWidth: BigDecimal)

case class CanvasBranchHandler(branchKey: SrcId, task: BranchTask, handler: CanvasHandler) extends BranchHandler {
  private type Handler = BranchMessage ⇒ World ⇒ World
  def exchange: Handler = message ⇒ messageHandler(message).andThen(toAlien(message)) //reset(local)
  private def widthGap = 20
  private def resize: Handler = message ⇒ local ⇒ {
    val sessionKey = message.header("X-r-session")
    val branchKey = message.header("X-r-branch")
    val sizes = message.header("X-r-canvas-sizes")
    val Array(canvasFontSize,canvasWidth) = sizes.split(" ").map(BigDecimal(_))
    val current = CanvasSizesKey.of(local)
    val ack = SendToAlienKey.of(local)(Seq(sessionKey),"ackCanvasResize",s"$branchKey $sizes") //todo merge with ackChanges
    val resize = if(
      current.nonEmpty &&
      canvasFontSize == current.get.canvasFontSize &&
      canvasWidth >= current.get.canvasWidth-widthGap &&
      canvasWidth <= current.get.canvasWidth
    ) Nil else List(
      CanvasContentKey.set(None),
      CanvasSizesKey.set(Option(CanvasSizes(canvasFontSize,canvasWidth)))
    )
    chain(ack :: resize)(local)
  }
  private def messageHandler: Handler = message ⇒ message.header("X-r-action") match {
    case "" ⇒ identity[World]
    case "canvasResize" ⇒ resize(message)
    case _ ⇒ handler.messageHandler(message)
  }
  private def value: Option[CanvasContent] ⇒ String =
    state ⇒ state.map(_.value).getOrElse("")
  private def toAlien: Handler = message ⇒ local ⇒ {
    val cState = CanvasContentKey.of(local)
    val (keepTo,freshTo,ackAll) = task.sending(local)
    val nState = if(keepTo.isEmpty && freshTo.isEmpty) None else if(
      cState.nonEmpty &&
      cState.get.until > System.currentTimeMillis &&
      message.header("X-r-action").isEmpty &&
      freshTo.isEmpty
    ) cState else Option(handler.view(local))
    val sends = if(value(cState)==value(nState)) Seq(freshTo).flatten else Seq(keepTo,freshTo).flatten
    val sendAll = chain(sends.map(_("showCanvasData",s"$branchKey ${value(nState)}")))
    CanvasContentKey.set(nState).andThen(sendAll).andThen(ackAll)(local)
  }
  def seeds: World ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}

