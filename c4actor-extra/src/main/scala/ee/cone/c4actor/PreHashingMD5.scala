package ee.cone.c4actor

import ee.cone.c4proto.BigDecimalFactory

case class PreHashingMD5() extends PreHashing {
  def wrap[T](value: T): PreHashed[T] = {
    val (first, second) = getProductHashOuter(value)
    new PreHashedMD5(first, second, value, (first ^ second).toInt)
  }

  private val murmur: MurmurHash3 = new MurmurHash3()

  def getProductHashOuter[Model](model: Model): (Long, Long) = {
    val innerMurMur = murmur.clone()
    getProductHash(model, innerMurMur)
    (innerMurMur.digest1, innerMurMur.digest2)
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

  def getProductHash[Model](model: Model, messengerInner: MurmurHash3): Unit = {
    model match {
      case i: List[_] ⇒
        i.foreach(getProductHash(_, messengerInner))
        messengerInner.updateByte((10 + i.length).toByte) // TODO this is sad, but with out it can cause stackOverFlow
      case f: PreHashedMD5[_] ⇒
        messengerInner.updateLong(f.MD5Hash1)
        messengerInner.updateLong(f.MD5Hash2)
        messengerInner.updateByte(6)
      case g: Product ⇒
        var counter = 0
        val arity = g.productArity
        while (counter < arity) {
          getProductHash(g.productElement(counter), messengerInner)
          counter = counter + 1
        }
        messengerInner.updateByte((10+arity).toByte)
      case e: String ⇒
        messengerInner.updateString(e)
        messengerInner.updateByte(5)
      case j: BigDecimal ⇒
        getProductHash(BigDecimalFactory.unapply(j).get, messengerInner) // TODO this allocates new ByteArray each time
      case a: Int ⇒
        messengerInner.updateInt(a)
        messengerInner.updateByte(4)
      case b: Long ⇒
        messengerInner.updateLong(b)
        messengerInner.updateByte(8)
      case c: Boolean ⇒
        messengerInner.updateBoolean(c)
        messengerInner.updateByte(1)
      case d: okio.ByteString ⇒
        messengerInner.updateBytes(d.toByteArray)
        messengerInner.updateByte(9)
      case h ⇒ FailWith.apply(s"Unsupported type ${h.getClass} by PreHashingMD5")
    }
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


