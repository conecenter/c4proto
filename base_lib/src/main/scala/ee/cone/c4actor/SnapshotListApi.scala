package ee.cone.c4actor

import ee.cone.c4proto.{Id, protocol}

trait SnapshotListProtocolAppBase
@protocol("SnapshotListProtocolApp") object SnapshotListRequestProtocol {
  @Id(0x36c9) case class S_ListSnapshotsRequest(
    @Id(0x36ca) source: String
  )

  @Id(0x36cb) case class S_ListSnapshotsResponse(
    @Id(0x36cc) source: String,
    @Id(0x36cd) snapshotsInfo: List[N_SnapshotInfoProto]
  )

  @Id(0x36ce) case class N_SnapshotInfoProto(
    @Id(0x36cf) subDirStr: String,
    @Id(0x36d0) offset: String,
    @Id(0x36d1) uuid: String,
    @Id(0x36d2) raw: Option[N_RawSnapshotProto],
    @Id(0x36d5) creationDate: Long
  )

  @Id(0x36d3) case class N_RawSnapshotProto(
    @Id(0x36d4) relativePath: String
  )
}
