package ee.cone.c4actor

object Utility {
  def minByOpt[A, B](list: TraversableOnce[A])(f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (list.isEmpty)
      None
    else
      Option(list.minBy(f)(cmp))
  }
}

object FailWith {
  def apply: String ⇒ Nothing = str ⇒ throw new Exception(str)
}

object Log2Pow2 {
  def apply(x: Int): Int = math.pow(2.0, (math.log(x)/math.log(2)).toInt).toInt
}