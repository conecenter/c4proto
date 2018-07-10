package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.ToPrimaryKey

import scala.annotation.tailrec
import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable.Seq

object TimeColored {
  def apply[R, F](color: String, tag: F, doNotPrint: Boolean = false, lowerBound: Long = 0L)(f: ⇒ R): R = {
    if (!doNotPrint) {
      val tagColored = PrintColored.makeColored(color)(tag)
      val timeStart = System.nanoTime()
      val result = f
      val endTime = (System.nanoTime() - timeStart) / 1000
      if (endTime / 1000 > lowerBound)
        println(s"[$tagColored] ${endTime / 1000},${(endTime % 1000 + 1000).toString.drop(1)}ms")
      result
    }
    else {
      f
    }
  }
}

object PrintColored {
  def apply[R](color: String = "", bgColor: String = "")(f: ⇒ R): R = {
    val result = f
    println(makeColored(color, bgColor)(result))
    result
  }

  def makeColored[R](color: String, bgColor: String = "")(f: R): String = {
    val colorAnsi = color match {
      case "y" ⇒ Console.YELLOW
      case "g" ⇒ Console.GREEN
      case "b" ⇒ Console.BLUE
      case "r" ⇒ Console.RED
      case "c" ⇒ Console.CYAN
      case "m" ⇒ Console.MAGENTA
      case "" ⇒ Console.BLACK
    }
    val bgColorAnsi = bgColor match {
      case "w" ⇒ Console.WHITE_B
      case "" ⇒ Console.BLACK_B
    }
    s"$colorAnsi$bgColorAnsi$f${Console.RESET}"
  }
}

object SingleInSeq {
  def apply[C](l: Seq[C]): Seq[C] = l match {
    case Seq() ⇒ l
    case Seq(_) ⇒ l
    case _ ⇒ FailWith.apply("Non single in SingleInSeq")
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

object MergeBySrcId {
  def apply[A <: Product](seqOfSeq: Seq[List[A]]): List[A] = {
    if (seqOfSeq.size == 1) {
      seqOfSeq.head
    } else {
      combine(seqOfSeq)
    }
  }

  private def combine[A <: Product](xss: Seq[List[A]]): List[A] = {
    val b = List.newBuilder[A]
    var its: Seq[List[(String, A)]] = xss.map(elem ⇒ elem.map(item ⇒ ToPrimaryKey(item) → item))
    var lastItem: Option[A] = None
    while (its.nonEmpty) {
      its = its.filter(_.nonEmpty)
      val (minElem, newIts) = minElemAndNewIter(its, lastItem)
      lastItem = if (minElem.isDefined) minElem else lastItem
      if (minElem.isDefined)
        b += minElem.get
      its = newIts
    }
    b.result
  }

  def minElemAndNewIter[A <: Product](in: Seq[List[(String, A)]], lastItem: Option[A]): (Option[A], Seq[List[(String, A)]]) = {
    if (in.nonEmpty) {
      val inWIndex: Seq[(List[(String, A)], Int)] = in.zipWithIndex
      val minElem: ((String, A), Int) = in.map(_.head).zipWithIndex.minBy(_._1._1)
      val ((_, item), minIndex) = minElem
      val newIn = inWIndex.map(pair ⇒ {
        val (list, index) = pair
        if (index != minIndex)
          pair
        else
          (list.tail, index)
      }
      )
      if (lastItem.isEmpty || (lastItem.isDefined && item != lastItem.get))
        (Option(item), newIn.unzip._1)
      else
        (None, newIn.unzip._1)
    } else {
      (None, in)
    }
  }


}

object DistinctBySrcIdFunctional {
  def apply[A <: Product](xs: Iterable[A]): List[A] = collectUnique(xs, Set(), Nil)

  @tailrec
  private def collectUnique[A <: Product](list: Iterable[A], set: Set[SrcId], accum: List[A]): List[A] =
    list match {
      case Nil => accum.reverse
      case x :: xs =>
        if (set(ToPrimaryKey(x))) collectUnique(xs, set, accum) else collectUnique(xs, set + ToPrimaryKey(x), x :: accum)
    }
}

trait LazyHashCodeProduct extends Product {
  lazy val savedHashCode: Int = runtime.ScalaRunTime._hashCode(this)

  override def hashCode(): Int = savedHashCode
}

object DistinctBySrcIdGit {
  def apply[Repr, A <: Product, That](xs: IterableLike[A, Repr])(implicit cbf: CanBuildFrom[Repr, A, That]): That =
    new ConeCollectionGit(xs).distinctBySrcId
}

class ConeCollectionGit[A <: Product, Repr](xs: IterableLike[A, Repr]) {
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