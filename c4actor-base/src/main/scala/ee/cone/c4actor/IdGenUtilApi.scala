package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import okio.ByteString

trait IdGenUtil extends Product {
  def md5(data: Array[Byte]*): String
  def toBytes(value: String): Array[Byte]
  def toBytes(value: Long): Array[Byte]
  def srcIdFromSrcIds(srcIdList: SrcId*): SrcId
  def srcIdFromStrings(stringList: String*): SrcId
  def srcIdFromSerialized(adapterId: Long, bytes: ByteString): SrcId
}
