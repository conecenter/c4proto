package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.Ranger

object IndexType extends Enumeration {
  type IndexType = Value
  val Default, Static, Dynamic, AutoStatic = Value
}

import IndexType._

abstract class RangerWithCl[By <: Product, Field](val byCl: Class[By], val fieldCl: Class[Field]) extends Ranger[By, Field] {
  def indexType: IndexType = Default
}
