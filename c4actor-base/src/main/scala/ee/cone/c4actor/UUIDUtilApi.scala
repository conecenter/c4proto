package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import okio.ByteString

trait UUIDUtil extends Product { //todo to base
  def srcIdFromSrcIds(srcIdList: SrcId*): SrcId
  def srcIdFromSerialized(adapterId: Long, bytes: ByteString): SrcId
}
