package ee.cone.c4gate.deep_session

import ee.cone.c4gate.SessionDataProtocol.RawDataNode
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object DeepSessionDataProtocol extends Protocol {

  import ee.cone.c4gate.SessionDataProtocol._

  @Id(0x0110) case class RawUserData(
    @Id(0x0111) srcId: String,
    @Id(0x0112) userId: String,
    @Id(0x0113) dataNode: Option[RawDataNode] // Always isDefined
  )

  @Id(0x0117) case class RawRoleData(
    @Id(0x0118) srcId: String,
    @Id(0x0119) roleId: String,
    @Id(0x0120) dataNode: Option[RawDataNode] // Always isDefined
  )

}