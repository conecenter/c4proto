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

  def getProductHashOuter[Model](model: Model): (Long, Long) = {
    val messengerInner: MessageDigest = messenger.clone().asInstanceOf[MessageDigest]
    getProductHash(model, messengerInner, new Array[Byte](9)) // 9 - sizeByte, numberBytes
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

  def writeBytes(messengerInner: MessageDigest, data: Array[Byte], writeType: Byte): Unit = {
    if (writeType == 8)
      messengerInner.update(data)
    else if (writeType == 4)
      messengerInner.update(data, 0, 5)
    else if (writeType == 1)
      messengerInner.update(data, 0, 2)
    else
      messengerInner.update(data :+ writeType)
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

  def getProductHash[Model](model: Model, messengerInner: MessageDigest, primitive: Array[Byte]): Unit = {
    val (bytes, writeType): (Array[Byte], Byte) = model match {
      /*case i: List[_] ⇒
        for {
          field ← i
        } yield getProductHash(field, messengerInner, primitive)
        (emptyArray, (50 + i.size).toByte)*/
      case g: Product ⇒
        val productSize = g.productArity
        for {
          i ← 0 until productSize // TODO replace with patternMatching
        } yield getProductHash(g.productElement(i), messengerInner, primitive)
        (emptyArray, (10 + productSize).toByte)
      case e: String ⇒
        (e.getBytes(UTF_8), 5)
      case f: PreHashedMD5[_] ⇒
        getProductHash(f.MD5Hash1, messengerInner, primitive)
        getProductHash(f.MD5Hash2, messengerInner, primitive)
        (emptyArray, 6)
      case j: BigDecimal ⇒
        getProductHash(BigDecimalFactory.unapply(j).get, messengerInner, primitive)
        (emptyArray, 7)
      case a: Int ⇒
        primitive(0) = 4
        primitive(1) = (a >> 24).toByte
        primitive(2) = (a >> 16).toByte
        primitive(3) = (a >> 8).toByte
        primitive(4) = (a >> 0).toByte
        (primitive, 4)
      case b: Long ⇒
        primitive(0) = 8
        primitive(1) = (b >> 56).toByte
        primitive(2) = (b >> 48).toByte
        primitive(3) = (b >> 40).toByte
        primitive(4) = (b >> 32).toByte
        primitive(5) = (b >> 24).toByte
        primitive(6) = (b >> 16).toByte
        primitive(7) = (b >> 8).toByte
        primitive(8) = (b >> 0).toByte
        (primitive, 8)
      case c: Boolean ⇒
        primitive(0) = 1
        primitive(1) = if (c) 1 else 0
        (primitive, 1)
      case d: okio.ByteString ⇒
        (d.toByteArray, 9)
      case h ⇒ FailWith.apply(s"Unsupported type ${h.getClass} by PreHashingMD5")
    }
    writeBytes(messengerInner, bytes, writeType)
  }
}

final class PreHashedMD5[T](val MD5Hash1: Long, val MD5Hash2: Long, val value: T, override val hashCode: Int) extends PreHashed[T] {
  override def toString: String = s"PreHashedMD5(${value.toString})"

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case a: PreHashedMD5[_] ⇒ a.MD5Hash1 == MD5Hash1 && a.MD5Hash2 == MD5Hash2
      case _ ⇒ false
    }
  }
}


