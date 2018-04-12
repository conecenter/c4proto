package ee.cone.c4actor

object Utility {
  def minByOpt[A, B](list: TraversableOnce[A])(f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (list.isEmpty)
      None
    else
      Option(list.minBy(f)(cmp))
  }
}
