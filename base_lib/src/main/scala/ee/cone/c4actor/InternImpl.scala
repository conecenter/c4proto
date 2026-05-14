package ee.cone.c4actor

import ee.cone.c4assemble.InnerApproximateIntern
import ee.cone.c4di.c4

import scala.annotation.tailrec

trait ApproximateIntern {
  def intern[T](ref: T): T
  def size: Int
  def count: Int
  def clear(): Unit
}

@c4("ProtoApp") final class ApproximateInternImpl extends ApproximateIntern {
  def intern[T](ref: T): T = InnerApproximateIntern.intern(ref, DefInternEq)
  def size: Int = InnerApproximateIntern.size
  def count: Int = InnerApproximateIntern.count
  def clear(): Unit = InnerApproximateIntern.clear()
}

object DefInternEq extends InnerApproximateIntern.Eq {
  @tailrec private def iterate(a: Iterator[AnyRef], b: Iterator[AnyRef]): Boolean = {
    val ah = a.hasNext
    val bh = b.hasNext
    !ah && !bh || ah && bh && equals(a.next, b.next) && iterate(a, b)
  }
  private def iterator(p: Product) = p.productIterator.asInstanceOf[Iterator[AnyRef]]
  def equals(a: AnyRef, b: AnyRef): Boolean =
    (a ne null) && (b ne null) && (a.getClass eq b.getClass) && (a match {
      case as: Seq[AnyRef@unchecked] => iterate(as.iterator, b.asInstanceOf[Seq[AnyRef]].iterator)
      case ap: Product => iterate(iterator(ap), iterator(b.asInstanceOf[Product]))
      case an: String => an.equals(b)
      case an: BigDecimal => an.bigDecimal.equals(b.asInstanceOf[BigDecimal].bigDecimal)
      case an: java.lang.Long => an.equals(b)
      case an: Integer => an.equals(b)
      case an: java.lang.Boolean => an.equals(b)
      case an: okio.ByteString => an.equals(b)
    })
}