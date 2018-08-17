package ee.cone.c4actor

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

import ee.cone.c4proto.BigDecimalFactory
import okio.ByteString

case class PreHashingMD5() extends PreHashing {
  def wrap[T](value: T): PreHashed[T] = new PreHashedMD5(getProductHashOuter(value), value, value.hashCode())

  private lazy val messenger: MessageDigest = MessageDigest.getInstance("MD5")

  def getProductHashOuter[Model](model: Model): String = {
    val messengerInner: MessageDigest = messenger.clone().asInstanceOf[MessageDigest]
    getProductHash(model, messengerInner)
    Base64.getEncoder.encodeToString(messengerInner.digest)
  }

  def writeBytes(messengerInner: MessageDigest, data: List[Array[Byte]]): Unit = {
    data.foreach { bytes ⇒
      val l = bytes.length
      messengerInner.update((l >> 24).toByte)
      messengerInner.update((l >> 16).toByte)
      messengerInner.update((l >> 8).toByte)
      messengerInner.update((l >> 0).toByte)
      messengerInner.update(bytes)
    }
  }

  def getProductHash[Model](model: Model, messengerInner: MessageDigest): Unit = {
    val bytes = model match {
      case a: Int ⇒ getInt(a) :: Nil
      case b: Long ⇒ getLong(b) :: Nil
      case c: Boolean ⇒ getBoolean(c) :: Nil
      case d: okio.ByteString ⇒ getByteString(d) :: Nil
      case e: String ⇒ getString(e) :: Nil
      case f: PreHashedMD5[_] ⇒ getPreHashedMD5(f) :: Nil
      case i: List[_] ⇒ getList(i)(messengerInner)
      case g: Product ⇒ getProduct(g)(messengerInner)
      case j: BigDecimal ⇒ getBigDecimal(j)
      case h ⇒ FailWith.apply(s"Unsupported type ${h.getClass} by PreHashingMD5")
    }
    writeBytes(messengerInner, bytes)
  }

  def getInt: Int ⇒ Array[Byte] = number ⇒ getLong(number.toLong)

  def getLong: Long ⇒ Array[Byte] = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(_).array()

  def getBoolean: Boolean ⇒ Array[Byte] = bool ⇒ Array.apply(if (bool) 1.toByte else 0.toByte)

  def getByteString: okio.ByteString ⇒ Array[Byte] = _.toByteArray

  def getString: String ⇒ Array[Byte] = _.getBytes(UTF_8)

  def getPreHashedMD5: PreHashedMD5[_] ⇒ Array[Byte] = preHashed ⇒ getString(preHashed.MD5Hash)

  def getProduct: Product ⇒ MessageDigest ⇒ List[Array[Byte]] = product ⇒ messenger ⇒ {
    for {
      field ← product.productIterator.toList
    } yield {
      getProductHash(field, messenger)
    }
    getString(product.getClass.getName) :: getLong(product.productArity) :: Nil
  }

  def getList: List[_] ⇒ MessageDigest ⇒ List[Array[Byte]] = list ⇒ messenger ⇒ {
    for {
      elem ← list
    } yield {
      getProductHash(elem, messenger)
    }
    getString(list.getClass.getName) :: getLong(list.size) :: Nil
  }

  def getBigDecimal: BigDecimal ⇒ MessageDigest ⇒ List[Array[Byte]] =
    getProduct(BigDecimalFactory.unapply _, _)
}

final class PreHashedMD5[T](val MD5Hash: String, val value: T, override val hashCode: Int) extends PreHashed[T] {
  override def toString: String = s"PreHashedMD5(${value.toString})"

  override def equals(obj: scala.Any): Boolean = {
    GlobalCounter.times = GlobalCounter.times + 1
    val now = System.currentTimeMillis()
    val answer = obj match {
      case a: PreHashedMD5[_] ⇒ a.MD5Hash == MD5Hash
      case _ ⇒ false
    }
    val after = System.currentTimeMillis()
    GlobalCounter.time = GlobalCounter.time + after - now
    answer
  }
}


