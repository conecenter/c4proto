package ee.cone.c4actor

// http://www.artima.com/pins1ed/object-equality.html
object PreHashingImpl extends PreHashing {
  def wrap[T](value: T): PreHashed[T] = new PreHashedImpl(value.hashCode, value)
}

final class PreHashedImpl[T](code: Int, val value: T) extends PreHashed[T] {
  override def hashCode: Int = code

  override def equals(that: Any): Boolean = {
    GlobalCounter.times = GlobalCounter.times + 1
    val now = System.currentTimeMillis()
    val answer = that match {
      case that: PreHashed[_] ⇒ value == that.value
      case _ => false
    }
    val after = System.currentTimeMillis()
    GlobalCounter.time = GlobalCounter.time + after - now
    answer
  }

  override def toString: String = s"PreHashed(${value.toString})"
}

/** ***********
  * try later:
  * override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)
  * class A(val b: Int)(val c: Int=b+b) extends E { private val d = b + c }
  * order: b, c, E-super, d
  * so to avoid possible non-initialized this, hashCode should be "c"?
  * pr in abstract class extending Product?
  * *************/
object GlobalCounter {
  var time: Long = 0
  var times: Long = 0
}