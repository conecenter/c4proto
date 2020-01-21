package ee.cone.c4actor.sandbox

import ee.cone.c4actor.sandbox.OtherProtocol.{D_Other, D_Other2}
import ee.cone.c4proto.{Id, protocol}

/*
  This file can be edited for learning purposes, feel free to experiment here
 */

trait SandboxProtocolsAppBase


@protocol("SandboxProtocolsApp") object SandboxProtocol   {

  @Id(0x0230) case class D_Sandbox(
    @Id(0x0231) srcId: String,
    @Id(0x0232) value: Int,
    @Id(0x0233) otherOrig: Option[D_Other],
    @Id(0x0234) list: List[D_Other2]
  )

}

@protocol("SandboxProtocolsApp") object OtherProtocol   {

  @Id(0x0235) case class D_Other(
    @Id(0x0236) srcId: String
  )

  @Id(0x0237) case class D_Other2(
    @Id(0x0238) srcId: String
  )

}