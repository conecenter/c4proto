package ee.cone.c4actor

//import ee.cone.c4proto.BigDecimalFactory

case class PreHashingMurMur3() extends PreHashing {
  def wrap[T](value: T): PreHashed[T] = {
    val innerMurMur = new MurmurHash3()
    calculateModelHash(value, innerMurMur)
    val first = innerMurMur.digest1
    val second = innerMurMur.digest2
    new PreHashedMurMur3(first, second, value, (first ^ second).toInt)
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

  def calculateModelHash[Model](model: Model, messengerInner: Java128HashInterface): Unit = {
    model match {
      case i: List[_] =>
        messengerInner.updateLong(50 + i.length)
        i.foreach(calculateModelHash(_, messengerInner)) // TODO this is sad, but with out it can cause stackOverFlow
      case g: Product =>
        val arity = g.productArity
        var counter = 0
        messengerInner.updateLong(10 + arity) // TODO can be [10, 10+22], 22 is Scala limit
        messengerInner.updateString(g.getClass.getName)
        while (counter < arity) {
          calculateModelHash(g.productElement(counter), messengerInner)
          counter = counter + 1
        }
      case f: PreHashedMurMur3[_] =>
        messengerInner.updateLong(6)
        messengerInner.updateLong(f.MD5Hash1)
        messengerInner.updateLong(f.MD5Hash2)
      case e: String =>
        messengerInner.updateLong(5)
        messengerInner.updateString(e)
      case j: BigDecimal =>
        calculateModelHash(BigDecimalFactory.unapply(j).get, messengerInner) // TODO this allocates new ByteArray each time
      case a: Int =>
        messengerInner.updateLong(4)
        messengerInner.updateInt(a)
      case b: Long =>
        messengerInner.updateLong(8)
        messengerInner.updateLong(b)
      case c: Boolean =>
        messengerInner.updateLong(1)
        messengerInner.updateBoolean(c)
      case d: okio.ByteString =>
        messengerInner.updateLong(9)
        messengerInner.updateBytes(d.toByteArray)
      /*case h: Array[Byte] =>
        messengerInner.updateLong(9)
        messengerInner.updateBytes(h)*/
      case h => FailWith.apply(s"Unsupported type ${h.getClass} by PreHashedMurMur3")
    }
  }
}

final class PreHashedMurMur3[T](val MD5Hash1: Long, val MD5Hash2: Long, val value: T, override val hashCode: Int) extends PreHashed[T] {
  override def toString: String = s"PreHashedMurMur3(${value.toString})"

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case a: PreHashedMurMur3[_] => a.MD5Hash1 == MD5Hash1 && a.MD5Hash2 == MD5Hash2
      case _ => false
    }
  }
}

object ArityGenerator{
  def main(args: Array[String]): Unit = {
    //val s = "          case a: Product3[_, _, _] =>\n            calculateModelHash(a._1, messengerInner)\n            calculateModelHash(a._2, messengerInner)\n            calculateModelHash(a._3, messengerInner)"
    for (
      i <- 1 to 22
    ) yield {
      val generic = Range(0, i).map(_ => "_").mkString(", ")
      println(s"          case a: Product$i[$generic] =>")
      for (
        j <- 1 to i
      ) yield {
        println(s"            calculateModelHash(a._$j, messengerInner)")
      }
    }
    println()
  }
}


