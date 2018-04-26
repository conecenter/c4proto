package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.Ranger

trait RangerWithCl[By <: Product, Field] extends Ranger[By, Field] {
  def byCl: Class[By]

  def fieldCl: Class[Field]
}
