package ee.cone.c4actor

trait ConditionCheckWithCl[By <: Product, Field] extends ConditionCheck[By, Field] {
  def byCl: Class[By]

  def fieldCl: Class[Field]
}
