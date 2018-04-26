package ee.cone.c4actor.hashsearch.condition

import ee.cone.c4actor.ConditionCheck

trait ConditionCheckWithCl[By <: Product, Field] extends ConditionCheck[By, Field] {
  def byCl: Class[By]

  def fieldCl: Class[Field]
}
