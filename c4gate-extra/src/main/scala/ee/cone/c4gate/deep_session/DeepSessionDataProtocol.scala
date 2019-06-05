package ee.cone.c4gate.deep_session

import ee.cone.c4gate.SessionAttrCat
import ee.cone.c4gate.SessionDataProtocol.N_RawDataNode
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol(SessionAttrCat) object DeepSessionDataProtocolBase   {

  import ee.cone.c4gate.SessionDataProtocol._

  @Id(0x0110) case class U_RawUserData(
    @Id(0x0111) srcId: String,
    @Id(0x0112) userId: String,
    @Id(0x0113) dataNode: Option[N_RawDataNode] // Always isDefined
  )

  @Id(0x0117) case class U_RawRoleData(
    @Id(0x0118) srcId: String,
    @Id(0x0119) roleId: String,
    @Id(0x0120) dataNode: Option[N_RawDataNode] // Always isDefined
  )

}