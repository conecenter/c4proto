package ee.cone.c4actor

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

import ee.cone.c4actor.Types.SrcId
import okio.ByteString

case class IdGenUtilImpl()(
  proto: MessageDigest = MessageDigest.getInstance("MD5")
) extends IdGenUtil {
  private def md5(data: Array[Byte]*): String = {
    val d = proto.clone().asInstanceOf[MessageDigest] // much faster than getInstance("MD5")
    data.foreach{ bytes ⇒
      val l = bytes.length
      d.update((l>>24).toByte)
      d.update((l>>16).toByte)
      d.update((l>> 8).toByte)
      d.update((l>> 0).toByte)
      d.update(bytes)
    }
    Base64.getEncoder.encodeToString(d.digest)
  }
  private def toBytes(value: String): Array[Byte] = value.getBytes(UTF_8)
  private def toBytes(value: Long): Array[Byte] =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def srcIdFromSrcIds(srcIdList: SrcId*): SrcId = md5(srcIdList.map(toBytes):_*)
  def srcIdFromStrings(stringList: String*): SrcId = md5(stringList.map(toBytes):_*)
  def srcIdFromSerialized(adapterId: Long, bytes: ByteString): SrcId =
    md5(toBytes(adapterId),bytes.toByteArray)
}
