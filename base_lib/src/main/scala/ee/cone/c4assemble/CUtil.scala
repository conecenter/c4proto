package ee.cone.c4assemble

object CUtil {
  type Tagged[U] = { type Tag = U }
  sealed trait CSeqTag[A]
  sealed trait COptTag[A]
  type CSeqVal[A] = Object with Tagged[CSeqTag[A]]
  type COptVal[A] = Object with Tagged[COptTag[A]]
  //
  private sealed trait CSeq[A] extends IndexedSeq[A]
  private object Seq0 extends CSeq[Object] {
    def apply(i: Int): Nothing = throw new Exception
    def length: Int = 0
  }
  private class Seq1[A](e0: A) extends CSeq[A] {
    def apply(i: Int): A = i match { case 0 => e0 }
    def length: Int = 1
  }
  private class Seq2[A](e0: A, e1: A) extends CSeq[A] {
    def apply(i: Int): A = i match { case 0 => e0 case 1 => e1 }
    def length: Int = 2
  }
  private class Seq3[A](e0: A, e1: A, e2: A) extends CSeq[A] {
    def apply(i: Int): A = i match { case 0 => e0 case 1 => e1 case 2 => e2 }
    def length: Int = 3
  }
  private class Seq4[A](e0: A, e1: A, e2: A, e3: A) extends CSeq[A] {
    def apply(i: Int): A = i match { case 0 => e0 case 1 => e1 case 2 => e2 case 3 => e3 }
    def length: Int = 4
  }
  private class Seq5[A](e0: A, e1: A, e2: A, e3: A, e4: A) extends CSeq[A] {
    def apply(i: Int): A = i match { case 0 => e0 case 1 => e1 case 2 => e2 case 3 => e3 case 4 => e4 }
    def length: Int = 5
  }
  private class Seq6[A](e0: A, e1: A, e2: A, e3: A, e4: A, e5: A) extends CSeq[A] {
    def apply(i: Int): A = i match { case 0 => e0 case 1 => e1 case 2 => e2 case 3 => e3 case 4 => e4 case 5 => e5 }
    def length: Int = 6
  }
  private class SeqN[A](inner: Array[AnyRef]) extends CSeq[A] {
    def apply(i: Int): A = inner(i).asInstanceOf[A]
    def length: Int = inner.length
  }
  def toVal[A](in: Seq[A]): CSeqVal[A] =
    (if(in.sizeCompare(1)==0 && !in.head.isInstanceOf[CSeq[_]]) in.head else toSeq(in)).asInstanceOf[CSeqVal[A]]
  def toSeq[A](in: Seq[A]): Seq[A] = in match {
    case v: CSeq[A] => v
    case v => v.size match {
      case 0 => Seq0.asInstanceOf[Seq[A]]
      case 1 => new Seq1(in.head)
      case 2 => new Seq2(in.head, in(1))
      case 3 => new Seq3(in.head, in(1), in(2))
      case 4 => new Seq4(in.head, in(1), in(2), in(3))
      case 5 => new Seq5(in.head, in(1), in(2), in(3), in(4))
      case 6 => new Seq6(in.head, in(1), in(2), in(3), in(4), in(5))
      case n =>
        val arr = new Array[Object](n)
        if(v.asInstanceOf[Seq[Object]].copyToArray(arr, 0, n) != n) throw new Exception
        new SeqN[A](arr)
    }
  }
  def toSeq[A](value: CSeqVal[A]): Seq[A] = value match {
    case v: CSeq[A] => v
    case v => new Seq1(v.asInstanceOf[A])
  }
  //
  def toVal[A](in: Option[A]): COptVal[A] = (in match {
    case None => in
    case Some(v) => if(v.isInstanceOf[Option[_]]) in else v
  }).asInstanceOf[COptVal[A]]
  def toOption[A](value: COptVal[A]): Option[A] = value match {
    case v: Option[A@unchecked] => v
    case v => Some(v.asInstanceOf[A])
  }
}
