package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.ToPrimaryKey

import scala.annotation.tailrec
import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom

object PrintColored{
  def apply[R](color: String)(f: ⇒ R): R = {
    val colorAnsi = color match {
      case "y" ⇒ Console.YELLOW
      case "g" ⇒ Console.GREEN
      case "b" ⇒ Console.BLUE
      case "r" ⇒ Console.RED
      case "" ⇒ Console.RESET
    }
    print(colorAnsi)
    val result = f
    println(result)
    print(Console.RESET)
    result
  }
}

object Utility {
  def minByOpt[A, B](list: TraversableOnce[A])(f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (list.isEmpty)
      None
    else
      Option(list.minBy(f)(cmp))
  }

  def reduceOpt[A](list: TraversableOnce[A])(f: (A, A) ⇒ A): Option[A] = {
    if (list.isEmpty)
      None
    else
      Option(list.reduce(f))
  }
}

object FailWith {
  def apply: String ⇒ Nothing = str ⇒ throw new Exception(str)
}

object Log2Pow2 {
  def apply(x: Int): Int = math.pow(2.0, (math.log(x) / math.log(2)).toInt).toInt
}

object DistinctBySrcIdFunctional {
  def apply[A<:Product](xs: Iterable[A]): List[A] = collectUnique(xs, Set(), Nil)

  @tailrec
  def collectUnique[A<:Product](list: Iterable[A], set: Set[SrcId], accum: List[A]): List[A] =
    list match {
      case Nil => accum.reverse
      case x :: xs =>
        if (set(ToPrimaryKey(x))) collectUnique(xs, set, accum) else collectUnique(xs, set + ToPrimaryKey(x), x :: accum)
    }
}

trait LazyHashCodeProduct extends Product{
  lazy val savedHashCode: Int = runtime.ScalaRunTime._hashCode(this)

  override def hashCode(): Int = savedHashCode
}

object DistinctBySrcIdGit{
  def apply[Repr, A<:Product, That](xs: IterableLike[A, Repr])(implicit cbf: CanBuildFrom[Repr, A, That]): That =
    new ConeCollectionGit(xs).distinctBySrcId
}

class ConeCollectionGit[A<:Product, Repr](xs: IterableLike[A, Repr]){
  def distinctBy[B, That](f: A => B)(implicit cbf: CanBuildFrom[Repr, A, That]): That = {
    val builder = cbf(xs.repr)
    val i = xs.iterator
    var set = Set[B]()
    while (i.hasNext) {
      val o = i.next
      val b = f(o)
      if (!set(b)) {
        set += b
        builder += o
      }
    }
    builder.result
  }
  def distinctBySrcId[That](implicit cbf: CanBuildFrom[Repr, A, That]): That = distinctBy(ToPrimaryKey(_))

  //to Use implicit:
  //  implicit def toDistinct[A, Repr](xs: IterableLike[A, Repr]): ConeCollection[A, Repr] = new ConeCollection(xs)
}