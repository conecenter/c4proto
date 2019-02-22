package ee.cone.c4actor.sandbox

import ee.cone.c4actor.{AssemblesApp, IdGenUtil, IdGenUtilApp, WithPK}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.sandbox.OtherProtocol.OrigOther
import ee.cone.c4actor.sandbox.SandboxProtocol.OrigSandbox
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
  otherOrig: OrigOther,
  origs: List[OrigSandbox]
)

case class RichSandboxPair(
  srcId: String,
  otherOrig: OrigOther,
  sandboxOrig: OrigSandbox
)

@assemble class SandboxJoinersBase(idGenUtil: IdGenUtil)   {

  def OrigSandboxToRichSandbox(
    srcId: SrcId,
    sandboxOrigs: Values[OrigSandbox]
  ): Values[(SrcId, RichSandbox)] =
    sandboxOrigs
      .map(orig ⇒ RichSandbox(orig.srcId, orig.value))
      .map(rich ⇒ (rich.srcId, rich))

  type OrigOtherSrcId = SrcId

  def OrigSandboxToOrigOtherId(
    srcId: SrcId,
    sandboxOrig: Each[OrigSandbox]
  ): Values[(OrigOtherSrcId, OrigSandbox)] =
    sandboxOrig.otherOrig match {
      case Some(otherOrig) ⇒ (otherOrig.srcId → sandboxOrig) :: Nil
      case None ⇒ Nil
    }

  def RichSandboxValueConstruction(
    srcId: SrcId,
    otherOrig: Each[OrigOther],
    @by[OrigOtherSrcId] sandboxOrigs: Values[OrigSandbox]
  ): Values[(SrcId, RichSandboxValue)] =
    WithPK(RichSandboxValue(otherOrig.srcId, otherOrig, sandboxOrigs.toList)) :: Nil

  def RichSandboxPairConstruction(
    srcId: SrcId,
    otherOrig: Each[OrigOther],
    @by[OrigOtherSrcId] sandboxOrig: Each[OrigSandbox]
  ): Values[(SrcId, RichSandboxPair)] = {
    val srcId = idGenUtil.srcIdFromSrcIds(
      otherOrig.srcId, sandboxOrig.srcId
    )
    WithPK(RichSandboxPair(srcId, otherOrig, sandboxOrig)) :: Nil
  }
}