package ee.cone.c4actor.sandbox

import ee.cone.c4actor.{AssemblesApp, IdGenUtil, IdGenUtilApp, WithPK}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.sandbox.OtherProtocol.D_Other
import ee.cone.c4actor.sandbox.SandboxProtocol.D_Sandbox
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}

/*
  This file can be edited for learning purposes, feel free to experiment here
 */

trait SandboxJoinersApp
  extends AssemblesApp
    with IdGenUtilApp {

  override def assembles: List[Assemble] =
    new SandboxJoiners(idGenUtil) :: super.assembles
}

case class RichSandbox(srcId: String, value: Int)

case class RichSandboxValue(
  srcId: String,
  otherOrig: D_Other,
  origs: List[D_Sandbox]
)

case class RichSandboxPair(
  srcId: String,
  otherOrig: D_Other,
  sandboxOrig: D_Sandbox
)

@assemble class SandboxJoinersBase(idGenUtil: IdGenUtil)   {

  def sandboxToRichSandbox(
    srcId: SrcId,
    sandboxOrigs: Values[D_Sandbox]
  ): Values[(SrcId, RichSandbox)] =
    sandboxOrigs
      .map(orig ⇒ RichSandbox(orig.srcId, orig.value))
      .map(rich ⇒ (rich.srcId, rich))

  type OtherSrcId = SrcId

  def sandboxToOrigOtherId(
    srcId: SrcId,
    sandboxOrig: Each[D_Sandbox]
  ): Values[(OtherSrcId, D_Sandbox)] =
    sandboxOrig.otherOrig match {
      case Some(otherOrig) ⇒ (otherOrig.srcId → sandboxOrig) :: Nil
      case None ⇒ Nil
    }

  def RichSandboxValueConstruction(
    srcId: SrcId,
    otherOrig: Each[D_Other],
    @by[OtherSrcId] sandboxOrigs: Values[D_Sandbox]
  ): Values[(SrcId, RichSandboxValue)] =
    WithPK(RichSandboxValue(otherOrig.srcId, otherOrig, sandboxOrigs.toList)) :: Nil

  def RichSandboxPairConstruction(
    srcId: SrcId,
    otherOrig: Each[D_Other],
    @by[OtherSrcId] sandboxOrig: Each[D_Sandbox]
  ): Values[(SrcId, RichSandboxPair)] = {
    val srcId = idGenUtil.srcIdFromSrcIds(
      otherOrig.srcId, sandboxOrig.srcId
    )
    WithPK(RichSandboxPair(srcId, otherOrig, sandboxOrig)) :: Nil
  }
}