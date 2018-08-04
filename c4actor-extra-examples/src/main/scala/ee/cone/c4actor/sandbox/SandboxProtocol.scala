package ee.cone.c4actor.sandbox

import ee.cone.c4actor.ProtocolsApp
import ee.cone.c4actor.sandbox.OtherProtocol.{OtherOrig1, OtherOrig2}
import ee.cone.c4proto.{Id, Protocol, protocol}

/*
  This file can be edited for learning purposes, feel free to experiment here
 */

trait SandboxProtocolsApp
  extends ProtocolsApp {
  override def protocols: List[Protocol] = SandboxProtocol :: OtherProtocol :: super.protocols
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