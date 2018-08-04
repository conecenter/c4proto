package ee.cone.c4actor.sandbox

import ee.cone.c4actor.PerformanceProtocol.NodeInstruction
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.sandbox.OtherProtocol.{OtherOrig1, OtherOrig2}
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, QProtocol}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.{BigDecimalProtocol, Id, Protocol, protocol}

/*
  This file can be edited for learning purposes, feel free to experiment here

  To start this app type the following into console:
  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.sandbox.Sandbox sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
 */

trait SandboxApp
  extends ProtocolsApp {
  override def protocols: List[Protocol] = SandboxProtocol :: OtherProtocol :: super.protocols

  override def assembles: List[Assemble] = new ChangingIndexAssemble(NodeInstruction("test", 0, 25000)) :: super.assembles
}


@protocol object SandboxProtocol extends Protocol {

  import OtherProtocol._

  @Id(0x0230) case class SandboxOrig(
    @Id(0x0231) srcId: String,
    @Id(0x0232) value: Int,
    @Id(0x0233) otherOrig: Option[OtherOrig1],
    @Id(0x0234) list: List[OtherOrig2]
  )

}

@protocol object OtherProtocol extends Protocol {

  @Id(0x0235) case class OtherOrig1(
    @Id(0x0236) srcId: String
  )

  @Id(0x0237) case class OtherOrig2(
    @Id(0x0238) srcId: String
  )

}