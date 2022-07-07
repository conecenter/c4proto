
package ee.cone.c4gate

import ee.cone.c4proto.{Id, protocol}

@protocol("RoomsConfProtocolApp") object RoomsConfProtocol {
  @Id(0x006D) case class S_RoomsConf(
    @Id(0x002A) srcId: String,
    @Id(0x006E) rooms: List[N_RoomConf],
  )
  case class N_RoomConf(
    @Id(0x0021) path: String,
    @Id(0x006F) content: String,
  )
}