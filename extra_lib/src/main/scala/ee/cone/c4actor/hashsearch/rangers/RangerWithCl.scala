package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.Ranger

object IndexType extends Enumeration {
  sealed trait IndexType extends Product
  case object Default extends IndexType
  case object Static extends IndexType
  case object Dynamic extends IndexType
  case object AutoStatic extends IndexType
}

import IndexType._

abstract class RangerWithCl[By <: Product, Field](val byCl: Class[By], val fieldCl: Class[Field]) extends Ranger[By, Field] {
  def indexType: IndexType = Default

  def prepareRequest: By => By
}
