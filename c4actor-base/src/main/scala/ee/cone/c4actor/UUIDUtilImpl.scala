package ee.cone.c4actor

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import okio.ByteString

import scala.collection.immutable.Seq

case class UUIDUtilImpl() extends UUIDUtil { // to base
  def uuid(data: String): UUID = UUID.nameUUIDFromBytes(data.getBytes(UTF_8))
  def uuidFromSeq(data: Seq[UUID]): UUID = {
    val b = ByteBuffer.allocate(java.lang.Long.BYTES * 2 * data.size)
    data.foreach(e ⇒ b.putLong(e.getMostSignificantBits).putLong(e.getLeastSignificantBits))
    UUID.nameUUIDFromBytes(b.array())
  }
  def uuidFromStrings(srcIdList: Seq[SrcId]): UUID =
    uuidFromSeq(srcIdList.map(uuid))
  def srcIdFromSrcIds(srcIdList: SrcId*): SrcId =
    uuidFromStrings(srcIdList.to[Seq]).toString
  def srcIdFromStrings(stringList: String*): SrcId =
    uuidFromStrings(stringList.to[Seq]).toString

  def toBytes(value: Long): Array[Byte] =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def srcIdFromSerialized(adapterId: Long, bytes: ByteString): SrcId =
    UUID.nameUUIDFromBytes(toBytes(adapterId) ++ bytes.toByteArray).toString


}
