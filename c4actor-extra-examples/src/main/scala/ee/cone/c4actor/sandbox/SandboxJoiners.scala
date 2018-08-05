package ee.cone.c4actor.sandbox

import ee.cone.c4actor.{AssemblesApp, IdGenUtil, WithPK}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.sandbox.OtherProtocol.OtherOrig
import ee.cone.c4actor.sandbox.SandboxProtocol.SandboxOrig
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}

/*
  This file can be edited for learning purposes, feel free to experiment here
 */

trait SandboxJoinersApp
  extends AssemblesApp {
  def idGenUtil: IdGenUtil

  override def assembles: List[Assemble] = new SandboxJoiners(idGenUtil) :: super.assembles
}

case class SandboxRich(srcId: String, value: Int)

case class SandboxValueRich(
  srcId: String,
  otherOrig: OtherOrig,
  origs: List[SandboxOrig]
)

case class SandboxPairRich(
  srcId: String,
  otherOrig: OtherOrig,
  sandboxOrig: SandboxOrig
)

@assemble class SandboxJoiners(idGenUtil: IdGenUtil) extends Assemble {

  def SandboxOrigToSandBoxRich(
    srcId: SrcId,
    sandboxOrigs: Values[SandboxOrig]
  ): Values[(SrcId, SandboxRich)] =
    sandboxOrigs
      .map(orig ⇒ SandboxRich(orig.srcId, orig.value))
      .map(rich ⇒ (rich.srcId, rich))

  type OtherOrigId = SrcId

  def SandboxOrigToOtherOrigId(
    srcId: SrcId,
    sandboxOrig: Each[SandboxOrig]
  ): Values[(OtherOrigId, SandboxOrig)] =
    sandboxOrig.otherOrig match {
      case Some(otherOrig) ⇒ (otherOrig.srcId → sandboxOrig) :: Nil
      case None ⇒ Nil
    }

  def SandboxValueRichConstruction(
    srcId: SrcId,
    otherOrig: Each[OtherOrig],
    @by[OtherOrigId] sandboxOrigs: Values[SandboxOrig]
  ): Values[(SrcId, SandboxValueRich)] =
    WithPK(SandboxValueRich(otherOrig.srcId, otherOrig, sandboxOrigs.toList)) :: Nil

  def SandboxPairRichConstruction(
    srcId: SrcId,
    otherOrig: Each[OtherOrig],
    @by[OtherOrigId] sandboxOrig: Each[SandboxOrig]
  ): Values[(SrcId, SandboxPairRich)] = {
    val srcId = idGenUtil.srcIdFromSrcIds(
      otherOrig.srcId, sandboxOrig.srcId
    )
    WithPK(SandboxPairRich(srcId, otherOrig, sandboxOrig)) :: Nil
  }
}