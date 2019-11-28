package ee.cone.c4actor_s3

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, protocol}

@protocol object S3ProtoBase {
  @Id(0x2604) case class E_S3FileRecord(
    @Id(0x2605) srcId: SrcId,
    @Id(0x2606) filename: String,
    @Id(0x2627) uploaded: Option[Long],
  )
}
