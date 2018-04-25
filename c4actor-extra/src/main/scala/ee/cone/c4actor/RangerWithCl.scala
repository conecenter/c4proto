package ee.cone.c4actor

trait RangerWithCl[By <: Product, Field] extends Ranger[By, Field] {
  def byCl: Class[By]

  def fieldCl: Class[Field]
}
