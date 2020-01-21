package ee.cone.c4actor_s3

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, protocol}

@protocol object S3Proto {
  @Id(0x1010) case class E_S3FileRecord(
    //@Id(0x2605) srcId: SrcId,
    @Id(0x1011) filename: SrcId,
    @Id(0x1012) uploaded: Option[Long],
  )
}
