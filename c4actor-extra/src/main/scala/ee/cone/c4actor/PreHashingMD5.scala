package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import ee.cone.c4proto.BigDecimalFactory

case class PreHashingMD5() extends PreHashing {
  def wrap[T](value: T): PreHashed[T] = {
    val (first, second) = getProductHashOuter(value)
    new PreHashedMD5(first, second, value, value.hashCode())
  }

  private lazy val messenger: MessageDigest = MessageDigest.getInstance("MD5")

  private val byteBufferSize: Int = 10096

  def getProductHashOuter[Model](model: Model): (Long, Long) = {
    val messengerInner: MessageDigest = messenger.clone().asInstanceOf[MessageDigest]
    getProductHash(model, messengerInner, new Array[Byte](8), new Array[Byte](byteBufferSize), 0)
    getTupledLong(messengerInner.digest)
  }

  def getTupledLong(bytes: Array[Byte]): (Long, Long) = {
    val ff = 0xff
    var long1: Long = 0L
    for {
      i ← 0 to 7
    } long1 = (long1 << 8) + (bytes(i) & ff)
    var long2 = 0
    for {
      i ← 0 to 7
    } long2 = (long2 << 8) + (bytes(i) & ff)
    (long1, long2)
  }

  def writeBytes(messengerInner: MessageDigest, data: Array[Byte], writeType: Byte, byteBuffer: Array[Byte], offset: Int): Int = {
    val dataLength = data.length
    if (dataLength + offset < byteBufferSize - 1) {
      byteBuffer(offset) = writeType
      copyArray(byteBuffer, data, offset + 1, writeType)
      offset + 1 + dataLength
    } else if (dataLength < byteBufferSize) {
      messengerInner.update(byteBuffer, 0, offset)
      copyArray(byteBuffer, data, 0, writeType)
      dataLength
    } else {
      println(s"Byte buffer too small for $dataLength bytes")
      messengerInner.update(byteBuffer)
      messengerInner.update(data :+ writeType)
      0
    }
  }

  def copyArray[T](to: Array[T], from: Array[T], offset: Int, writeType: Int): Unit = {
    val copyCount = if (writeType == 1 || writeType == 4 || writeType == 8) writeType else from.length
    //println(to.length, from.length, copyCount)
    for {
      i ← 0 until copyCount
    } to(offset + i) = from(i)
  }

  /*
  1 - Boolean,
  4 - Int,
  8 - Long,
  50 - List,
  10 - Product,
  5 - String,
  6 - PreHashedMD5,
  7 - BigDecimal,
  9 - okio.ByteString
   */

  val emptyArray: Array[Byte] = new Array[Byte](0)

  def getProductHash[Model](model: Model, messengerInner: MessageDigest, primitive: Array[Byte], byteBuffer: Array[Byte], offset: Int): Int = {
    model match {
      /*case i: List[_] ⇒
        for {
          field ← i
        } yield getProductHash(field, messengerInner, primitive)
        (emptyArray, (50 + i.size).toByte)*/
      case g: Product ⇒
        val newOffset = g.productIterator.foldLeft(offset)((count, elem) ⇒ getProductHash(elem, messengerInner, primitive, byteBuffer, count))
        (emptyArray, (10 + g.productArity).toByte, newOffset)
        writeBytes(messengerInner, emptyArray, (10 + g.productArity).toByte, byteBuffer, newOffset)
      case e: String ⇒
        writeBytes(messengerInner, e.getBytes(UTF_8), 5, byteBuffer, offset)
      case f: PreHashedMD5[_] ⇒
        val newOffset = getProductHash(f.MD5Hash1, messengerInner, primitive, byteBuffer, offset)
        val newOffset2 = getProductHash(f.MD5Hash2, messengerInner, primitive, byteBuffer, newOffset)
        writeBytes(messengerInner, emptyArray, 6, byteBuffer, newOffset2)
      case j: BigDecimal ⇒
        writeBytes(messengerInner, emptyArray, 7, byteBuffer, getProductHash(BigDecimalFactory.unapply(j).get, messengerInner, primitive, byteBuffer, offset))
      case a: Int ⇒
        primitive(0) = (a >> 24).toByte
        primitive(1) = (a >> 16).toByte
        primitive(2) = (a >> 8).toByte
        primitive(3) = (a >> 0).toByte
        writeBytes(messengerInner, primitive, 4, byteBuffer, offset)
      case b: Long ⇒
        primitive(0) = (b >> 56).toByte
        primitive(1) = (b >> 48).toByte
        primitive(2) = (b >> 40).toByte
        primitive(3) = (b >> 32).toByte
        primitive(4) = (b >> 24).toByte
        primitive(5) = (b >> 16).toByte
        primitive(6) = (b >> 8).toByte
        primitive(7) = (b >> 0).toByte
        writeBytes(messengerInner, primitive, 8, byteBuffer, offset)
      case c: Boolean ⇒
        primitive(0) = if (c) 1 else 0
        writeBytes(messengerInner, primitive, 1, byteBuffer, offset)
      case d: okio.ByteString ⇒
        writeBytes(messengerInner, d.toByteArray, 9, byteBuffer, offset)
      case h ⇒ FailWith.apply(s"Unsupported type ${h.getClass} by PreHashingMD5")
    }
  }
}

final class PreHashedMD5[T](val MD5Hash1: Long, val MD5Hash2: Long, val value: T, override val hashCode: Int) extends PreHashed[T] {
  override def toString: String = s"PreHashedMD5(${value.toString})"

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case a: PreHashedMD5[_] ⇒ a.MD5Hash1 == MD5Hash1 && a.MD5Hash2 == MD5Hash2 // TODO check string and long comparision
      case _ ⇒ false
    }
  }
}


