package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset
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

  case class N_SnapshotInfoProto(
    @Id(0x371c) relativePath: String,
    @Id(0x371d) creationDate: Long
  )
}

trait ConsumerBeginningOffset {
  def get(): NextOffset
}
