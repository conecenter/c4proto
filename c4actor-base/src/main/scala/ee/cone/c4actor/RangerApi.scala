package ee.cone.c4actor

trait Ranger[By<:Product,Field] extends Product {
  def ranges: By => (Field => List[By], PartialFunction[Product,List[By]])
}
